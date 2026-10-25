import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/**
 * Draws the EarthLife app icon (an original blocky planet with a small airliner) and writes:
 *   earthlife.png (256x256) and earthlife.ico (16, 32, 48, 256 px, PNG-compressed entries; Windows Vista+).
 * Run: java packaging/MakeIcon.java packaging/windows
 */
public class MakeIcon {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(out);
        BufferedImage base = draw(32);                      // 32x32 "pixel art" master
        int[] sizes = {16, 32, 48, 256};
        byte[][] pngs = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            BufferedImage img = scale(base, sizes[i]);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(img, "png", b);
            pngs[i] = b.toByteArray();
            if (sizes[i] == 256) Files.write(out.resolve("earthlife.png"), pngs[i]);
        }
        try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("earthlife.ico"))))) {
            d.writeShort(0); d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) sizes.length));
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                d.writeByte(sizes[i] >= 256 ? 0 : sizes[i]); d.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
                d.writeByte(0); d.writeByte(0);
                d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) 32));
                d.writeInt(Integer.reverseBytes(pngs[i].length)); d.writeInt(Integer.reverseBytes(offset));
                offset += pngs[i].length;
            }
            for (byte[] p : pngs) d.write(p);
        }
        System.out.println("Wrote " + out.resolve("earthlife.ico") + " and earthlife.png");
    }

    static BufferedImage draw(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        double c = (n - 1) / 2.0, r = n * 0.44;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double dx = x - c, dy = y - c;
            if (dx * dx + dy * dy > r * r) continue;
            // Blocky continents from a fixed pattern (2x2 "blocks")
            int bx = x / 2, by = y / 2;
            boolean land = ((bx * 7 + by * 13) % 11 < 4) || (bx > 4 && bx < 9 && by > 3 && by < 8) || (bx > 9 && by > 9 && bx < 13);
            int col = land ? ((bx + by) % 3 == 0 ? 0xFF4E8F2E : 0xFF5DA336) : ((bx + by) % 2 == 0 ? 0xFF2D6FB8 : 0xFF3479C6);
            if (dx * dx + dy * dy > (r - 1.5) * (r - 1.5)) col = 0xFF1B3A5C;           // dark rim
            if (dx < -2 && dy < -2 && dx * dx + dy * dy < (r * 0.45) * (r * 0.45)) col = brighten(col);  // highlight
            img.setRGB(x, y, col);
        }
        // Little white airliner crossing the top-right
        int[][] plane = {{22, 7}, {23, 7}, {24, 7}, {25, 7}, {26, 7}, {24, 6}, {24, 8}, {23, 5}, {23, 9}, {21, 6}, {21, 8}, {27, 7}};
        for (int[] p : plane) img.setRGB(p[0] * n / 32, p[1] * n / 32, 0xFFFFFFFF);
        return img;
    }

    static int brighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 30), g = Math.min(255, ((c >> 8) & 255) + 30), b = Math.min(255, (c & 255) + 30);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            img.setRGB(x, y, src.getRGB(x * src.getWidth() / size, y * src.getHeight() / size));   // nearest-neighbour keeps pixels crisp
        return img;
    }
}
