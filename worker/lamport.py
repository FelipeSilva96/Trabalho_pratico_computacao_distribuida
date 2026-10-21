import threading


class LamportClock:
    def __init__(self):
        self._value = 0
        self._lock = threading.Lock()

    def tick(self):
        with self._lock:
            self._value += 1
            return self._value

    def receive(self, received_value):
        with self._lock:
            self._value = max(self._value, int(received_value)) + 1
            return self._value

    def get(self):
        with self._lock:
            return self._value
