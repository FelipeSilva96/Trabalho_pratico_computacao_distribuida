import tempfile
import unittest
from pathlib import Path

from fault_injection import maybe_crash_before_commit


class _CrashForTest(Exception):
    pass


def pretend_crash():
    raise _CrashForTest()


class FaultInjectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = self.temp.name
        self.env = {
            "INJECT_2PC_COMMIT_CRASH_ONCE": "1",
            "INJECT_2PC_FAULT_TOKEN": "teste-unico-0001",
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_desativado_sem_variavel_de_ambiente(self):
        self.assertFalse(maybe_crash_before_commit(
            'tx-001','worker-windows',self.root,environ={},terminate=pretend_crash))
        self.assertFalse(list(Path(self.root).iterdir()))

    def test_restrito_ao_worker_windows(self):
        self.assertFalse(maybe_crash_before_commit(
            'tx-001','worker-lubuntu',self.root,environ=self.env,terminate=pretend_crash))
        self.assertFalse(list(Path(self.root).iterdir()))

    def test_token_invalido_rejeitado_sem_encerrar(self):
        env=dict(self.env,INJECT_2PC_FAULT_TOKEN='../../etc/passwd')
        with self.assertRaises(ValueError):
            maybe_crash_before_commit('tx-001','worker-windows',self.root,environ=env,terminate=pretend_crash)
        self.assertFalse(list(Path(self.root).iterdir()))

    def test_marcador_persistente_evitar_mais_de_um_crash(self):
        with self.assertRaises(_CrashForTest):
            maybe_crash_before_commit('tx-001','worker-windows',self.root,environ=self.env,terminate=pretend_crash)
        marker=Path(self.root)/'.fault-commit-once-teste-unico-0001.marker'
        self.assertTrue(marker.is_file())
        self.assertIn('tx=tx-001',marker.read_text())
        # Uma nova chamada, inclusive apos restart, segue normalmente.
        self.assertFalse(maybe_crash_before_commit('tx-001','worker-windows',self.root,environ=self.env,terminate=pretend_crash))
        self.assertFalse(maybe_crash_before_commit('tx-002','worker-windows',self.root,environ=self.env,terminate=pretend_crash))

    def test_token_diferente_permita_novo_teste(self):
        with self.assertRaises(_CrashForTest):
            maybe_crash_before_commit('tx-001','worker-windows',self.root,environ=self.env,terminate=pretend_crash)
        env=dict(self.env,INJECT_2PC_FAULT_TOKEN='teste-unico-0002')
        with self.assertRaises(_CrashForTest):
            maybe_crash_before_commit('tx-002','worker-windows',self.root,environ=env,terminate=pretend_crash)


if __name__ == '__main__':
    unittest.main()
