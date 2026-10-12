package com.redactado.noray.web.dashboard;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Abstract images drawn on the server for the component showcase (the node has no image content of its own). */
final class SampleImages {

    private static volatile List<byte[]> cache;

    private SampleImages() {
    }

    static List<byte[]> all() {
        List<byte[]> c = cache;
        if (c == null) {
            synchronized (SampleImages.class) {
                if (cache == null) cache = generate();
                c = cache;
            }
        }
        return c;
    }

    private static List<byte[]> generate() {
        int[][] palettes = {
                {0x0b0b0b, 0x3a3a3a, 0xd9d9d9}, {0x05070d, 0x1f4fd1, 0xa9c1ff}, {0x0a0a0a, 0x6b3fd4, 0xf2e9ff},
                {0x050505, 0x0a7d3e, 0xc8f5dc}, {0x0d0806, 0x9a5a00, 0xffe2b8}, {0x000000, 0xc4201b, 0xffd6d4}};
        List<byte[]> out = new ArrayList<>();
        Random rnd = new Random(7);
        for (int[] pal : palettes) {
            BufferedImage img = new BufferedImage(480, 360, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(pal[0]));
            g.fillRect(0, 0, 480, 360);
            for (int i = 0; i < 5; i++) {
                float x = rnd.nextFloat() * 480, y = rnd.nextFloat() * 360, r = 120 + rnd.nextFloat() * 260;
                g.setPaint(new RadialGradientPaint(new Point2D.Float(x, y), r, new float[]{0f, 0.55f, 1f},
                        new Color[]{alpha(pal[i % 2 == 0 ? 2 : 1], 200), alpha(pal[1], 90), alpha(pal[0], 0)},
                        MultipleGradientPaint.CycleMethod.NO_CYCLE));
                g.fillRect(0, 0, 480, 360);
            }
            g.dispose();
            for (int y = 0; y < 360; y++) { // film grain: a little noise on every pixel
                for (int x = 0; x < 480; x++) {
                    int p = img.getRGB(x, y), n = rnd.nextInt(17) - 8;
                    img.setRGB(x, y, clamp(((p >> 16) & 0xff) + n) << 16 | clamp(((p >> 8) & 0xff) + n) << 8 | clamp((p & 0xff) + n));
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                ImageIO.write(img, "png", bos);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            out.add(bos.toByteArray());
        }
        return List.copyOf(out);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static Color alpha(int rgb, int a) {
        return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, a);
    }
}
