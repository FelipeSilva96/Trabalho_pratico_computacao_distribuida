import io
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from replica_store import ReplicaConflict, ReplicaCorrupted, ReplicaStore


def png(color=(120, 30, 200)):
    buffer = io.BytesIO()
    Image.new("RGB", (7, 5), color).save(buffer, format="PNG")
    return buffer.getvalue()


class ReplicaStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = ReplicaStore(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_salva_le_checksum_e_tamanho(self):
        original = png()
        data = self.store.save("resultado-001", original)
        self.assertEqual(data.size_bytes, len(original))
        self.assertEqual(len(data.sha256), 64)
        self.assertEqual(self.store.read("resultado-001"), original)

    def test_repeticao_identica_e_idempotente(self):
        first = self.store.save("mesma-tarefa", png())
        second = self.store.save("mesma-tarefa", png())
        self.assertEqual(first, second)

    def test_erro_para_mesmo_id_com_conteudo_distinto(self):
        self.store.save("resultado-002", png())
        with self.assertRaises(ReplicaConflict):
            self.store.save("resultado-002", png((200, 50, 5)))

    def test_persistencia_apos_reabrir_store(self):
        self.store.save("resultado-003", png())
        outro_processo_simulado = ReplicaStore(self.tmp.name)
        self.assertEqual(outro_processo_simulado.read("resultado-003"), png())

    def test_corrupcao_de_arquivo_e_detectada(self):
        self.store.save("resultado-004", png())
        (Path(self.tmp.name) / "committed" / "resultado-004.png").write_bytes(b"corrompido")
        with self.assertRaises(ReplicaCorrupted):
            self.store.read("resultado-004")

    def test_id_invalido_e_rejeitado(self):
        with self.assertRaises(ValueError):
            self.store.save("../fora", png())

    def test_imagem_invalida_e_rejeitada(self):
        with self.assertRaises(ValueError):
            self.store.save("nao-png", b"conteudo qualquer")

    def test_resultado_inexistente(self):
        with self.assertRaises(KeyError):
            self.store.read("nao-existe")


if __name__ == "__main__":
    unittest.main()
