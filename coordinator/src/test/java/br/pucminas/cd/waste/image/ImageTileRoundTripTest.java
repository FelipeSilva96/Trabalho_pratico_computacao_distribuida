package br.pucminas.cd.waste.image;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageTileRoundTripTest {
    @Test
    void imageWithOddDimensionsCanBeSplitAndReassembledWithoutLosingPixels() throws Exception {
        BufferedImage original = new BufferedImage(285, 177, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                original.setRGB(x, y, 0xff000000 | ((x * 3) & 255) << 16
                        | ((y * 7) & 255) << 8 | ((x + y) & 255));
            }
        }
        var tiles = ImageTiler.split(original, 2, 2);
        assertEquals(4, tiles.size());
        var placements = tiles.stream()
                .map(t -> new ImageAssembler.Placement(t, t.png()))
                .toList();
        byte[] rebuilt = ImageAssembler.assemble(285, 177, placements);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(rebuilt));
        assertEquals(285, result.getWidth());
        assertEquals(177, result.getHeight());
        for (int y = 0; y < 177; y++) {
            for (int x = 0; x < 285; x++) {
                assertEquals(original.getRGB(x, y), result.getRGB(x, y));
            }
        }
    }
}
