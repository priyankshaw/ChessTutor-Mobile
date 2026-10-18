class HTTPException(Exception):
    def __init__(self, status_code, detail):
        super().__init__(str(detail))
        self.status_code = status_code
        self.detail = str(detail)


class Cancelled(Exception):
    pass
