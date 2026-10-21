package br.pucminas.cd.waste.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

public final class ImageTiler {
    private ImageTiler() {}

    public record Tile(String id, int x, int y, int width, int height, byte[] png) {}

    public static List<Tile> split(BufferedImage image, int rows, int columns)
            throws IOException {
        if (image == null || rows < 1 || columns < 1
                || rows > 8 || columns > 8
                || rows > image.getHeight() || columns > image.getWidth()) {
            throw new IllegalArgumentException("Grade de blocos invalida para esta imagem");
        }

        List<Tile> tiles = new ArrayList<>();
        int width = image.getWidth();
        int height = image.getHeight();

        for (int row = 0; row < rows; row++) {
            int y0 = row * height / rows;
            int y1 = (row + 1) * height / rows;
            for (int column = 0; column < columns; column++) {
                int x0 = column * width / columns;
                int x1 = (column + 1) * width / columns;
                int tileWidth = x1 - x0;
                int tileHeight = y1 - y0;
                BufferedImage part = image.getSubimage(x0, y0, tileWidth, tileHeight);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(part, "png", output)) {
                    throw new IOException("Nao foi possivel codificar o bloco como PNG");
                }
                tiles.add(new Tile("r" + row + "c" + column,
                        x0, y0, tileWidth, tileHeight, output.toByteArray()));
            }
        }
        return List.copyOf(tiles);
    }
}
