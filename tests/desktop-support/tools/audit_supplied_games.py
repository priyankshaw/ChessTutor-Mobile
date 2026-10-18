"""Replay numbered games and compare feedback with reviews in a supplied text file."""
import argparse
import asyncio
import csv
import io
import json
import re
from pathlib import Path
from unittest.mock import patch

import chess.pgn

from backend.engine.evaluator import StockfishEvaluator
from backend.services.analyzer import GameAnalyzer
from backend.services import analyzer as analyzer_module
from tools.audit_learner_games import audit
from tools.paths import ARTIFACT_DIR


def read_games(path):
    games = []
    text = path.read_text(encoding='utf-8-sig')
    blocks = re.split(r'(?m)^#\s*\d+[^\n]*\n', text)[1:]
    for number, block in enumerate(blocks, 1):
        pgn, review = re.split(r'(?i)\breview\s*:', block, maxsplit=1)
        pgn = re.sub(r'\n(?:[ \t]*\n){2,}', '\n\n', pgn[pgn.index('['):].strip())
        game = chess.pgn.read_game(io.StringIO(pgn))
        if not game or game.errors or not list(game.mainline_moves()):
            raise ValueError(f'Game {number}: invalid PGN')
        labels = re.findall(r'(?i)\b(book|best|excellent|good|inaccuracy|inaccuraccy|mistake|miss|blunder|great|brilliant|forced)\b', review)
        labels = [label.lower().replace('inaccuraccy', 'inaccuracy') for label in labels]
        if len(labels) != len(list(game.mainline_moves())):
            raise ValueError(f'Game {number}: review length does not match moves')
        games.append((game, pgn, labels))
    if not games:
        raise ValueError('No numbered games found')
    return games


def labels_compatible(reference, actual):
    return reference == actual or {reference, actual} == {'best', 'excellent'}


def comparison_rows(number, data, labels):
    positions = data['positions']
    if len(positions) != len(labels):
        raise ValueError(f'Game {number}: analyzed move count does not match reference')
    rows = []
    for position, label in zip(positions, labels):
        grade = position['classification']
        rows.append({'game': number, 'ply': position['ply'], 'move_number': position['move_number'],
                     'color': position['color'], 'move': position['san'], 'reference': label,
                     'actual': grade['classification'], 'matched': grade['classification'] == label,
                     'acceptable': labels_compatible(label, grade['classification']),
                     'grading_loss': round(grade.get('grading_loss', grade['win_loss']), 2),
                     'chance_loss': round(grade['win_loss'], 2), 'cp_loss': grade['cp_loss']})
    return rows


async def run(path, prefix, depth=18):
    games = read_games(path)
    evaluator = StockfishEvaluator()
    report = ['# Supplied-game feedback audit', '',
              'Native Stockfish. Label agreement and legal-feedback checks are reported separately. '
              'Every reference and actual label appears in the accompanying moves CSV.', '']
    summary = []
    comparisons = []
    errors = []
    try:
        if not evaluator.engine:
            raise RuntimeError('Native Stockfish is required')
        for number, (game, pgn, labels) in enumerate(games, 1):
            inputs = []
            original_classifier = analyzer_module.classify_move
            def record_classification(**kwargs):
                inputs.append({key: value.uci() if key == 'played_move' else value
                               for key, value in kwargs.items() if key not in ('board_before', 'board_after')})
                return original_classifier(**kwargs)
            with patch.object(analyzer_module, 'classify_move', side_effect=record_classification):
                data = await GameAnalyzer(evaluator).analyze_game(pgn, depth=depth)
            assert len(inputs) == len(data['positions'])
            data['classification_inputs'] = inputs
            data['reference_labels'] = labels
            (ARTIFACT_DIR / f'{prefix}_{number}.json').write_text(json.dumps(data), encoding='utf-8')
            positions = data['positions']
            comparisons.extend(comparison_rows(number, data, labels))
            matching = sum(row['matched'] for row in comparisons if row['game'] == number)
            acceptable = sum(row['acceptable'] for row in comparisons if row['game'] == number)
            severe = sum(position['classification']['classification'] in ('mistake', 'miss', 'blunder')
                         for position in positions)
            reference_severe = sum(label in ('mistake', 'miss', 'blunder') for label in labels)
            inaccuracies = sum(position['classification']['classification'] == 'inaccuracy' for position in positions)
            reference_inaccuracies = labels.count('inaccuracy')
            summary.append({'game': number, 'plies': len(positions), 'matching': matching, 'acceptable': acceptable,
                            'severe': severe, 'reference_severe': reference_severe,
                            'inaccuracies': inaccuracies, 'reference_inaccuracies': reference_inaccuracies,
                            'accuracy': data['accuracy']})
            report.extend([f'## Game {number}: {game.headers["White"]} vs {game.headers["Black"]}', '',
                           f'Matched {matching}/{len(positions)} labels; severe {severe} vs reference {reference_severe}; '
                           f'inaccuracies {inaccuracies} vs reference {reference_inaccuracies}.', ''])
            try:
                audit(game, data, report)
            except AssertionError as exc:
                errors.append(f'Game {number}: {exc}')
                report.append(f'Feedback check failed: {exc}')
            print(f'Game {number}: {len(positions)} plies, accuracy {data["accuracy"]["white"]}/{data["accuracy"]["black"]}, '
                  f'severe {severe}/{reference_severe}, inaccuracies {inaccuracies}/{reference_inaccuracies}, '
                  f'matched {matching}/{len(positions)}', flush=True)
            (ARTIFACT_DIR / f'{prefix}_report.md').write_text('\n'.join(report) + '\n', encoding='utf-8')
            (ARTIFACT_DIR / f'{prefix}_summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
            with (ARTIFACT_DIR / f'{prefix}_moves.csv').open('w', encoding='utf-8', newline='') as output:
                writer = csv.DictWriter(output, fieldnames=list(comparisons[0]))
                writer.writeheader()
                writer.writerows(comparisons)
        if errors:
            raise AssertionError('\n'.join(errors))
        matching = sum(row['matched'] for row in comparisons)
        print(f'Label agreement: {matching}/{len(comparisons)} ({matching / len(comparisons):.1%})', flush=True)
        acceptable = sum(row['acceptable'] for row in comparisons)
        print(f'User rule (Best/Excellent interchangeable): {acceptable}/{len(comparisons)} ({acceptable / len(comparisons):.1%})', flush=True)
        totals = [f'Best/Excellent interchangeable: **{acceptable}/{len(comparisons)} ({acceptable / len(comparisons):.1%})**.', '',
                  '| Reference label | Correct / total | Predicted |', '| --- | ---: | ---: |']
        for label in ('good', 'inaccuracy', 'mistake', 'miss', 'blunder', 'brilliant'):
            reference = [row for row in comparisons if row['reference'] == label]
            correct = sum(row['matched'] for row in reference)
            predictions = sum(row['actual'] == label for row in comparisons)
            print(f'{label}: {correct}/{len(reference)} reference moves match; {predictions} predicted', flush=True)
            totals.append(f'| {label} | {correct}/{len(reference)} | {predictions} |')
            if label == 'brilliant' and correct != len(reference):
                errors.append('Not every reference Brilliant move is Brilliant')
        report[4:4] = totals + ['']
        (ARTIFACT_DIR / f'{prefix}_report.md').write_text('\n'.join(report) + '\n', encoding='utf-8')
        if acceptable != len(comparisons):
            errors.append(f'{len(comparisons) - acceptable}/{len(comparisons)} reference labels still differ (only Best/Excellent swaps allowed)')
        if errors:
            raise AssertionError('\n'.join(errors))
        print(f'Legal-feedback checks PASS: {len(games)} games, {len(comparisons)} plies', flush=True)
    finally:
        evaluator.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', type=Path)
    parser.add_argument('--output-prefix', default='supplied')
    parser.add_argument('--depth', type=int, default=18)
    args = parser.parse_args()
    asyncio.run(run(args.path, args.output_prefix, args.depth))
