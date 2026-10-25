package earthlife.core.npc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/**
 * Paints original 64x64 Minecraft-format player skins (classic 4-px arms) from a seed: skin tone, hair style and
 * colour, eyes, mouth, shirt, trousers and shoes, optionally a work uniform. NPCs therefore render with the vanilla
 * player model and look like players, without copying anyone's real skin.
 *
 * UV layout follows the standard skin format: head 0..32 x 0..16, body/right arm/right leg in row 16..32,
 * left leg 16..32 x 48..64 and left arm 32..48 x 48..64.
 */
public final class SkinGenerator {
    public enum Uniform { NONE, PILOT, AIRPORT, JANITOR, CONSTRUCTION, FARMER, STORE }

    static final int[] SKIN = {0xFFF5D0B0, 0xFFE8B98F, 0xFFD39B6A, 0xFFB57A4A, 0xFF8D5524, 0xFF6B3F1D, 0xFF4A2C17};
    static final int[] HAIR = {0xFF1B1B1B, 0xFF3B2414, 0xFF6A4423, 0xFFA0703A, 0xFFE0C068, 0xFFB5462C, 0xFF8C8C8C, 0xFFEEEEEE};
    static final int[] CLOTH = {0xFF2F5DA8, 0xFFB03030, 0xFF2E7D32, 0xFF6A1B9A, 0xFFF9A825, 0xFF37474F, 0xFFEF6C00, 0xFF00838F, 0xFFEEEEEE, 0xFF5D4037};
    static final int[] PANTS = {0xFF263238, 0xFF1A237E, 0xFF3E2723, 0xFF424242, 0xFF5D6D7E, 0xFF795548};

    public static BufferedImage paint(long seed, Uniform uniform) {
        Random r = new Random(seed);
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int skin = SKIN[r.nextInt(SKIN.length)], hair = HAIR[r.nextInt(HAIR.length)];
        int shirt = CLOTH[r.nextInt(CLOTH.length)], pants = PANTS[r.nextInt(PANTS.length)], shoes = r.nextBoolean() ? 0xFF212121 : 0xFF5D4037;
        int eye = new int[]{0xFF3B6EA5, 0xFF4E342E, 0xFF2E7D32, 0xFF263238}[r.nextInt(4)];
        boolean longHair = r.nextInt(3) == 0, beard = r.nextInt(5) == 0, shortSleeves = r.nextBoolean();
        switch (uniform) {
            case PILOT -> { shirt = 0xFFF5F5F5; pants = 0xFF0D1B3E; }
            case AIRPORT -> { shirt = 0xFF1565C0; pants = 0xFF263238; }
            case JANITOR -> { shirt = 0xFF607D8B; pants = 0xFF607D8B; }
            case CONSTRUCTION -> { shirt = 0xFFFF9800; pants = 0xFF1A237E; }
            case FARMER -> { shirt = 0xFF8D6E63; pants = 0xFF283593; }
            case STORE -> { shirt = 0xFFC62828; }
            default -> {}
        }
        // Head (8x8x8 cube at 0,0): top 8..16 x 0..8, faces in row 8..16 (right 0, front 8, left 16, back 24).
        fill(img, 8, 0, 8, 8, hair);                         // top
        fill(img, 16, 0, 8, 8, skin);                        // bottom
        fill(img, 0, 8, 32, 8, skin);                        // all sides
        fill(img, 0, 8, 32, 2 + (longHair ? 1 : 0), hair);   // hairline all around
        fill(img, 24, 8, 8, longHair ? 8 : 5, hair);         // back of head
        fill(img, 0, 8, 2, longHair ? 7 : 4, hair); fill(img, 16 + 6, 8, 2, longHair ? 7 : 4, hair); // sides
        fill(img, 8 + 1, 12, 2, 1, 0xFFFFFFFF); fill(img, 8 + 5, 12, 2, 1, 0xFFFFFFFF);            // eye whites
        img.setRGB(8 + 2, 12, eye); img.setRGB(8 + 5, 12, eye);                                    // pupils (inner)
        fill(img, 8 + 3, 14, 2, 1, darker(skin, 0.6));                                              // mouth
        img.setRGB(8 + 3, 13, darker(skin, 0.85)); img.setRGB(8 + 4, 13, darker(skin, 0.85));       // nose
        if (beard) { fill(img, 8, 14, 8, 2, hair); fill(img, 8 + 3, 14, 2, 1, darker(skin, 0.6)); }
        // Body 8x12x4: top 20..28 x 16..20, faces row 20..32 (right 16, front 20, left 28, back 32).
        fill(img, 16, 20, 24, 12, shirt); fill(img, 20, 16, 16, 4, shirt);
        if (uniform == Uniform.PILOT) { fill(img, 23, 20, 2, 9, 0xFF0D1B3E); fill(img, 21, 21, 2, 1, 0xFFFFD54F); }     // tie + wings
        if (uniform == Uniform.CONSTRUCTION || uniform == Uniform.JANITOR) { fill(img, 20, 24, 8, 1, 0xFFE0E0E0); fill(img, 32, 24, 8, 1, 0xFFE0E0E0); } // reflective stripe
        if (uniform == Uniform.FARMER) { fill(img, 21, 20, 1, 12, 0xFF283593); fill(img, 26, 20, 1, 12, 0xFF283593); }  // overall straps
        // Belt line
        fill(img, 16, 30, 24, 1, darker(pants, 0.7));
        // Right arm 4x12x4 at 40,16 ; left arm at 32,48
        paintLimb(img, 40, 16, shortSleeves ? 4 : 10, shirt, skin);
        paintLimb(img, 32, 48, shortSleeves ? 4 : 10, shirt, skin);
        // Right leg at 0,16 ; left leg at 16,48
        paintLimb(img, 0, 16, 9, pants, shoes); fill(img, 0, 16, 16, 4, pants);
        paintLimb(img, 16, 48, 9, pants, shoes); fill(img, 16, 48, 16, 4, pants);
        if (uniform == Uniform.CONSTRUCTION) fill(img, 8, 0, 8, 8, 0xFFFFC107);                    // hard hat (top)
        if (uniform == Uniform.PILOT) { fill(img, 8, 0, 8, 8, 0xFF0D1B3E); fill(img, 0, 8, 32, 2, 0xFF0D1B3E); }     // cap
        return img;
    }

    private static void paintLimb(BufferedImage img, int u, int v, int coveredRows, int cloth, int end) {
        fill(img, u + 4, v, 8, 4, cloth);                  // top/bottom
        fill(img, u, v + 4, 16, 12, end);                  // sides default to skin/shoes
        fill(img, u, v + 4, 16, coveredRows, cloth);       // sleeve / trouser leg
    }

    private static void fill(BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int i = x; i < x + w; i++) for (int j = y; j < y + h; j++) img.setRGB(i, j, argb);
    }

    private static int darker(int argb, double f) {
        int r = (int) (((argb >> 16) & 255) * f), g = (int) (((argb >> 8) & 255) * f), b = (int) ((argb & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static byte[] png(long seed, Uniform uniform) {
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(paint(seed, uniform), "png", out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static Uniform uniformFor(earthlife.core.jobs.JobType j) {
        return switch (j) {
            case PILOT -> Uniform.PILOT;
            case CHECKIN_AGENT, GATE_AGENT -> Uniform.AIRPORT;
            case JANITOR -> Uniform.JANITOR;
            case CONSTRUCTION_WORKER -> Uniform.CONSTRUCTION;
            case FARMER -> Uniform.FARMER;
            case STORE_CLERK -> Uniform.STORE;
            default -> Uniform.NONE;
        };
    }
}
