package io.github.immichuploader;

import org.jcodec.api.awt.AWTSequenceEncoder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

public final class TestMediaFactory {
    private TestMediaFactory() {
    }

    public static Path jpeg(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        ImageIO.write(image(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)),
                random.nextInt(10_000)), "jpg", file.toFile());
        return file;
    }

    public static Path png(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        ImageIO.write(image(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)),
                random.nextInt(10_000)), "png", file.toFile());
        return file;
    }

    public static Path mp4(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int base = random.nextInt(200);
        AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(file.toFile(), 10);
        for (int i = 0; i < 10; i++) {
            encoder.encodeImage(image(new Color((base + i * 5) % 256, 80, 120), base + i));
        }
        encoder.finish();
        return file;
    }

    private static BufferedImage image(Color color, int offset) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, 64, 64);
            graphics.setColor(Color.WHITE);
            graphics.drawString("test " + offset, 8, 32);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
