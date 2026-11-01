import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Run from the repository root: java tools/GenerateIcons.java. JDK 17 only. */
class GenerateIcons {
    private static final Color INK = new Color(0x788596);
    private static final Color BRAND = new Color(0x5B7BD5);
    private static final Color SUCCESS = new Color(0x278F95);
    private static final Color ERROR = new Color(0xD95763);
    private static final Color WARNING = new Color(0xBD861F);
    private static final Color INFO = new Color(0x518DCE);
    private static final List<String> NAMES = List.of("edtai", "history", "new_chat", "settings", "attach",
            "file", "copy", "retry", "edit", "auto", "read_only", "status_ok", "status_error", "status_off",
            "review", "review_error", "review_warning", "review_info");

    public static void main(String[] args) throws Exception {
        Path directory = Path.of("bundles/io.github.hansglorias.edtai.ui/icons");
        for (String name : NAMES) {
            for (int scale : new int[] { 1, 2 }) {
                ImageIO.write(icon(name, scale), "png", directory.resolve(name + (scale == 2 ? "@2x" : "") + ".png").toFile());
            }
        }
        Files.createDirectories(Path.of("target"));
        preview(NAMES, "icon-preview");
        preview(List.of("edtai", "review", "review_error", "review_warning", "review_info"), "brand-preview");
    }

    private static void preview(List<String> names, String file) throws Exception {
        // Contact sheet: actual size plus 2x, on both light and dark backgrounds.
        BufferedImage sheet = new BufferedImage(720, 80 + names.size() * 46, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0xF7F9FC));
        g.fillRect(0, 0, 360, sheet.getHeight());
        g.setColor(new Color(0x232930));
        g.fillRect(360, 0, 360, sheet.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        for (int column = 0; column < 2; column++) {
            g.setColor(column == 0 ? new Color(0x303946) : new Color(0xE2E7EE));
            g.drawString("EDT AI / 16 px + 32 px", column * 360 + 24, 38);
            for (int i = 0; i < names.size(); i++) {
                int x = column * 360 + 24;
                int y = 64 + i * 46;
                g.drawImage(icon(names.get(i), 1), x, y + 8, null);
                g.drawImage(icon(names.get(i), 2), x + 40, y, null);
                g.drawString(names.get(i), x + 94, y + 21);
            }
        }
        g.dispose();
        ImageIO.write(sheet, "png", Path.of("target/" + file + ".png").toFile());
    }

    private static BufferedImage icon(String name, int scale) {
        // Supersampling keeps diagonals smooth; @2x is rendered separately from the same geometry.
        int sample = 4;
        BufferedImage source = new BufferedImage(16 * scale * sample, 16 * scale * sample, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = source.createGraphics();
        g.scale(scale * sample, scale * sample);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(INK);
        switch (name) {
            case "edtai" -> {
                g.setColor(BRAND);
                frame(g);
                Path2D core = new Path2D.Double();
                core.moveTo(8, 4.5); core.lineTo(11.5, 8); core.lineTo(8, 11.5);
                core.lineTo(4.5, 8); core.closePath();
                g.fill(core);
            }
            case "history" -> {
                g.draw(new RoundRectangle2D.Double(1.8, 2, 12.4, 12, 2.6, 2.6));
                path(g, 5.8, 2.5, 5.8, 13.5);
                path(g, 8.5, 5, 11.5, 5);
                path(g, 8.5, 8, 11.5, 8);
                path(g, 8.5, 11, 10, 11);
            }
            case "new_chat" -> {
                path(g, 7.5, 2.5, 4, 2.5, 2.5, 4, 2.5, 12, 4, 13.5, 12, 13.5, 13.5, 12, 13.5, 8.5);
                path(g, 7, 9, 7.5, 6.5, 12, 2, 14, 4, 9.5, 8.5, 7, 9);
            }
            case "settings" -> {
                path(g, 2, 4, 5, 4); path(g, 8, 4, 14, 4);
                path(g, 2, 8, 9, 8); path(g, 12, 8, 14, 8);
                path(g, 2, 12, 4, 12); path(g, 7, 12, 14, 12);
                circle(g, 6.5, 4, 1.5); circle(g, 10.5, 8, 1.5); circle(g, 5.5, 12, 1.5);
            }
            case "attach" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(10.5, 5); p.lineTo(6, 9.5); p.curveTo(4.3, 11.2, 2.8, 8.8, 4.3, 7.3);
                p.lineTo(9.2, 2.4); p.curveTo(12.2, -0.6, 16.3, 3.8, 13.2, 6.9);
                p.lineTo(7.4, 12.7); p.curveTo(3.1, 17, -1, 11.3, 2.6, 7.7); g.draw(p);
            }
            case "file" -> {
                path(g, 9, 1.8, 3.5, 1.8, 3.5, 14.2, 12.5, 14.2, 12.5, 5.3, 9, 1.8, 9, 5.3, 12.5, 5.3);
                path(g, 6, 8.3, 10, 8.3); path(g, 6, 11, 10, 11);
            }
            case "copy" -> {
                g.draw(new RoundRectangle2D.Double(5.2, 5.2, 8.5, 8.5, 2, 2));
                path(g, 10.7, 2.3, 3.3, 2.3, 2.3, 3.3, 2.3, 10.7);
            }
            case "retry" -> {
                g.drawArc(2, 2, 12, 12, 43, 290);
                path(g, 9.5, 2, 12.8, 3.5, 13, 0.8);
            }
            case "edit" -> {
                path(g, 2, 13.8, 3, 9.8, 11, 1.8, 14.2, 5, 6.2, 13, 2, 13.8);
                path(g, 9.3, 3.5, 12.5, 6.7);
            }
            case "auto" -> path(g, 9, 1.5, 3.5, 8.5, 7.5, 8.5, 7, 14.5, 12.5, 7.5, 8.5, 7.5, 9, 1.5);
            case "read_only" -> {
                g.draw(new RoundRectangle2D.Double(3, 7, 10, 7, 2, 2));
                g.drawArc(5, 2, 6, 9, 0, 180);
                path(g, 8, 10, 8, 11.5);
            }
            case "status_ok" -> {
                g.setColor(SUCCESS); circle(g, 8, 8, 5.6); path(g, 5, 8, 7, 10, 11.2, 5.8);
            }
            case "status_error" -> {
                g.setColor(ERROR); circle(g, 8, 8, 5.6); path(g, 6, 6, 10, 10); path(g, 10, 6, 6, 10);
            }
            case "status_off" -> { circle(g, 8, 8, 5.6); path(g, 5.5, 8, 10.5, 8); }
            case "review", "review_error", "review_warning", "review_info" -> {
                // Линейка редактора бывает уже и ниже 16 px: рисунок помещается в 12 px.
                if (!name.equals("review")) {
                    g.translate(2, 2);
                    g.scale(0.75, 0.75);
                }
                g.setColor(switch (name) { case "review_error" -> ERROR; case "review_warning" -> WARNING;
                    case "review_info" -> INFO; default -> BRAND; });
                frame(g);
                if (name.equals("review")) {
                    path(g, 4.8, 8, 7, 10, 11.2, 5.8);
                } else if (name.equals("review_error")) {
                    path(g, 6, 6, 10, 10); path(g, 10, 6, 6, 10);
                } else if (name.equals("review_warning")) {
                    path(g, 8, 5, 8, 8.4); dot(g, 8, 11);
                } else {
                    dot(g, 8, 5); path(g, 8, 7.5, 8, 11.5);
                }
            }
            default -> throw new IllegalArgumentException(name);
        }
        g.dispose();
        BufferedImage result = new BufferedImage(16 * scale, 16 * scale, BufferedImage.TYPE_INT_ARGB);
        g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, result.getWidth(), result.getHeight(), null);
        g.dispose();
        return result;
    }

    private static void frame(Graphics2D g) {
        path(g, 5.3, 1.9, 1.9, 1.9, 1.9, 5.3);
        path(g, 10.7, 1.9, 14.1, 1.9, 14.1, 5.3);
        path(g, 1.9, 10.7, 1.9, 14.1, 5.3, 14.1);
        path(g, 10.7, 14.1, 14.1, 14.1, 14.1, 10.7);
    }

    private static void path(Graphics2D g, double... points) {
        Path2D p = new Path2D.Double(); p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
        g.draw(p);
    }

    private static void circle(Graphics2D g, double x, double y, double radius) {
        g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
    }

    private static void dot(Graphics2D g, double x, double y) {
        g.fill(new Ellipse2D.Double(x - 0.8, y - 0.8, 1.6, 1.6));
    }
}
