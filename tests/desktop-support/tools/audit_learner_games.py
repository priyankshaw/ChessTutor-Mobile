"""Replay a supplied PGN and six public games; retain real engine responses for UI QA.

Fetch games first through /api/games/chesscom/PriyankShaw8?limit=6 into
artifacts/learner_profile_audit.json. Run with --refresh to regenerate coaching
from those engine responses without rerunning Stockfish.
"""
import argparse
import io
import json
from pathlib import Path
from tools.paths import ARTIFACT_DIR

import chess
import chess.pgn
import httpx

from backend.engine.explainer import generate_heuristic_explanation
from backend.engine.move_evidence import attacks


def refresh_explanations(game, data):
    board = game.board()
    for move, position in zip(game.mainline_moves(), data['positions']):
        before = board.copy()
        board.push(move)
        best = position['best_move']
        best_info = {'move_uci': best['uci'], 'move_san': best['san'], 'pv_san': best['pv_san']}
        steps = position['explanation']['refutation_steps']
        mate_context = [fact for fact in position['explanation']['teaching'].get('evidence', [])
                        if fact.startswith('Stockfish already finds forced mate against you before this move')]
        reply = {'best_move': {'move_uci': steps[0]['uci'], 'pv_san': [step['san'] for step in steps]}} if steps else None
        position['explanation'] = generate_heuristic_explanation(
            before, board, move, best_info, reply, position['classification']['classification'],
            sacrifice_info=position['classification'].get('sacrifice'))
        position['explanation']['teaching']['evidence'].extend(mate_context)


def audit(game, data, report):
    moves = list(game.mainline_moves())
    assert not game.errors
    assert len(data['positions']) == len(moves)
    board = game.board()
    report.extend(['| Move | Rating | Loss (cp) | Played effect | Best alternative |',
                   '| --- | --- | ---: | --- | --- |'])
    for move, position in zip(moves, data['positions']):
        assert position['fen_before'] == board.fen()
        assert position['san'] == board.san(move)
        before = board.copy()
        board.push(move)
        assert position['fen_after'] == board.fen()
        explanation = position['explanation']
        rating = position['classification']
        assert (position['color'] == 'white') == before.turn
        for field in ('why_played', 'why_best'):
            assert len(explanation[field].split()) <= 30, (position['ply'], field, explanation[field])
            assert len(explanation[f'complete_{field}'].split()) <= 40, (position['ply'], field, explanation[f'complete_{field}'])
            assert 'Engine reply:' not in explanation[field]
        if rating['classification'] == 'forced':
            assert before.legal_moves.count() == 1
        if rating['classification'] == 'blunder':
            assert position['eval'].get('mate') is not None and position['eval']['mate'] < 0 or rating['win_loss'] >= 20 or (
                position['eval']['best_win_percent'] >= 68 and position['eval']['win_percent'] <= 42)
        for origin, steps in ((before, explanation['best_steps']), (board, explanation['refutation_steps'])):
            replay = origin.copy()
            for step in steps:
                assert len(step['complete_text'].split()) <= 40, step
                replay.push_san(step['san'])
        for field in ('played_line', 'comparison_line'):
            replay = before.copy()
            for san in explanation['teaching'].get(field, []):
                replay.push_san(san)
        for idea in explanation['complete_evidence']:
            replay = before.copy()
            for index, san in enumerate(idea['line']):
                assert idea['steps'][index]['san'] == san
                replay.push_san(san)
                if index == idea['arrival_index']:
                    assert chess.parse_square(idea['target']) in attacks(replay, chess.parse_square(idea['landing']))
            assert replay.piece_at(chess.parse_square(idea['landing'])) == chess.Piece(chess.PAWN, before.turn)
        if explanation['teaching']['theme'] == 'Piece safety':
            reply = board.parse_san(explanation['refutation_steps'][0]['san'])
            replay = board.copy()
            replay.push(reply)
            assert not any(candidate.to_square == reply.to_square and replay.is_capture(candidate) for candidate in replay.legal_moves)
        if explanation['teaching']['theme'] == 'Resolve the attack' and 'under attack' in explanation['why_played']:
            target = chess.parse_square(explanation['teaching']['squares'][0])
            if board.piece_at(target) == chess.Piece(chess.PAWN, before.turn):
                for capture in board.legal_moves:
                    if (capture.to_square != target or not board.is_capture(capture)
                            or board.piece_at(capture.from_square).piece_type != chess.PAWN):
                        continue
                    traded = board.copy()
                    traded.push(capture)
                    assert not any(reply.to_square == target and traded.is_capture(reply) for reply in traded.legal_moves), (
                        position['ply'], 'Recapturable pawn presented as a safety problem', explanation['why_played'])
        if explanation['teaching'].get('comparison_only'):
            assert f": {explanation['why_best']}" not in explanation['why_played'], (
                position['ply'], 'Best-card explanation repeated in played feedback')
        if before.is_castling(move):
            assert 'castling is still blocked' not in explanation['why_played']
        label = f"{position['move_number']}{'.' if position['color'] == 'white' else '...'}{position['san']}"
        report.append(f"| {label} | {rating['name']} | {rating['cp_loss']} | {explanation['complete_why_played']} | {position['best_move']['san']}: {explanation['complete_why_best']} |")
    return len(moves)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--refresh', action='store_true')
    parser.add_argument('--fetch-profile', action='store_true', help='Fetch the six latest public PriyankShaw8 games.')
    parser.add_argument('--fixtures-only', action='store_true', help='Run the supplied game and the preserved queen-trade game without a profile download.')
    args = parser.parse_args()
    if args.fetch_profile:
        response = httpx.get('http://127.0.0.1:8000/api/games/chesscom/PriyankShaw8', params={'limit': 6}, timeout=60)
        response.raise_for_status()
        (ARTIFACT_DIR / 'learner_profile_audit.json').write_text(json.dumps(response.json()), encoding='utf-8')
    if args.fixtures_only:
        profile = [{'id': 'english_fixture', 'white': 'PriyankShaw8', 'black': 'matoust',
                    'url': 'https://www.chess.com/game/live/184800290476',
                    'pgn': Path('backend/tests/data/profile_english_review.pgn').read_text()}]
    else:
        profile = json.loads((ARTIFACT_DIR / 'learner_profile_audit.json').read_text(encoding='utf-8-sig'))['games']
        assert len(profile) == 6
    fixtures = [{'id': 'supplied', 'white': 'You', 'black': 'Stockfish 1800', 'url': '',
                 'pgn': Path('backend/tests/data/stockfish_1800_long_review.pgn').read_text()}] + profile
    report = ['# Beginner explanation audit', '',
              'Supplied game plus preserved public-game regression.' if args.fixtures_only else 'Supplied 160-ply game plus six games fetched from the Chess.com public API through the profile importer.',
              'Native Stockfish responses; every board, displayed line, severity threshold and summary length checked. Reports also inspected for useful causal teaching.', '']
    total = 0
    for metadata in fixtures:
        game = chess.pgn.read_game(io.StringIO(metadata['pgn']))
        output = (ARTIFACT_DIR / f"learner_{metadata['id']}_verified.json")
        if args.refresh:
            data = json.loads(output.read_text())
            refresh_explanations(game, data)
        else:
            response = httpx.post('http://127.0.0.1:8000/api/analyze',
                                  json={'pgn': metadata['pgn'], 'depth': 16}, timeout=360)
            response.raise_for_status()
            data = response.json()
        output.write_text(json.dumps(data), encoding='utf-8')
        name = f"{metadata['white']} vs {metadata['black']}"
        report.extend([f"## [{name}]({metadata['url']})" if metadata['url'] else f'## {name}', ''])
        count = audit(game, data, report)
        total += count
        report.append('')
        report_path = 'artifacts/learner_live_audit.md' if args.fixtures_only else 'artifacts/learner_explanation_audit.md'
        Path(report_path).write_text('\n'.join(report) + '\n', encoding='utf-8')
        print(f"PASS: {metadata['id']}, {count} plies", flush=True)
    print(f'PASS: {len(fixtures)} games, all {total} plies.', flush=True)


if __name__ == '__main__':
    main()
