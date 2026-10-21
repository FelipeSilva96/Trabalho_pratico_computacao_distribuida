package br.pucminas.cd.waste.image;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

public final class ImageAssembler {
    private ImageAssembler() {}

    public record Placement(ImageTiler.Tile tile, byte[] resultPng) {}

    public static byte[] assemble(int width, int height, List<Placement> placements)
            throws IOException {
        if (width <= 0 || height <= 0 || placements == null || placements.isEmpty()) {
            throw new IllegalArgumentException("Parametros de montagem invalidos");
        }

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            for (Placement placement : placements) {
                ImageTiler.Tile tile = placement.tile();
                BufferedImage piece = ImageIO.read(new ByteArrayInputStream(placement.resultPng()));
                if (piece == null || piece.getWidth() != tile.width()
                        || piece.getHeight() != tile.height()) {
                    throw new IOException("Resultado do bloco " + tile.id() + " invalido");
                }
                graphics.drawImage(piece, tile.x(), tile.y(), null);
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, "png", output)) {
            throw new IOException("Nao foi possivel montar a imagem PNG");
        }
        return output.toByteArray();
    }
}
