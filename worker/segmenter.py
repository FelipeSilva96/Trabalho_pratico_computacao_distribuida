import io
import sys

import numpy as np

from PIL import Image
from skimage.segmentation import slic, mark_boundaries


def segmentar_imagem(
    image_bytes,
    n_segments=200,
    compactness=10.0
):
    """
    Executa segmentação SLIC em uma imagem.

    Parâmetros:
        image_bytes: bytes da imagem original.
        n_segments: número aproximado de superpixels.
        compactness: controle de compactação dos segmentos.

    Retorno:
        bytes da imagem segmentada em PNG.
    """

    # 1. Converter os bytes em imagem RGB
    with Image.open(io.BytesIO(image_bytes)) as arquivo:
        imagem = arquivo.convert("RGB")

    # 2. Transformar a imagem em matriz NumPy
    pixels = np.asarray(imagem)

    # 3. Executar o algoritmo SLIC
    segmentos = slic(
        pixels,
        n_segments=n_segments,
        compactness=compactness,
        start_label=1,
        channel_axis=-1
    )

    # 4. Desenhar os limites dos superpixels
    resultado = mark_boundaries(
        pixels,
        segmentos,
        color=(1, 0, 0)
    )

    # 5. Converter a imagem para valores de 0 a 255
    resultado = (
        np.clip(resultado, 0, 1) * 255
    ).astype(np.uint8)

    # 6. Criar imagem com os segmentos destacados
    imagem_resultado = Image.fromarray(resultado)

    # 7. Converter a imagem em bytes PNG
    buffer = io.BytesIO()

    imagem_resultado.save(
        buffer,
        format="PNG"
    )

    return buffer.getvalue()


if __name__ == "__main__":

    if len(sys.argv) != 3:
        print(
            "Uso: python segmenter.py "
            "entrada.jpg resultado.png"
        )
        sys.exit(1)

    caminho_entrada = sys.argv[1]
    caminho_saida = sys.argv[2]

    with open(caminho_entrada, "rb") as arquivo:
        imagem_original = arquivo.read()

    imagem_segmentada = segmentar_imagem(
        imagem_original
    )

    with open(caminho_saida, "wb") as arquivo:
        arquivo.write(imagem_segmentada)

    print(
        f"Segmentação concluída: {caminho_saida}"
    )
