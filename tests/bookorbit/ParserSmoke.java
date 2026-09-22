import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

/**
 * Runs the APK's production parsers under app_process on a disposable emulator.
 * No instrumentation dependencies or installation over the reader are needed.
 */
public final class ParserSmoke {
    private static Object epub;
    private static Object cfi;
    private static int checks;

    public static void main(String[] args) throws Exception {
        epub = Class.forName("com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage")
            .getField("Companion").get(null);
        cfi = Class.forName("com.chmouel.liseur.data.bookorbit.BookOrbitCfi")
            .getField("Companion").get(null);
        String opf = "<package xmlns=\"http://www.idpf.org/2007/opf\">"
            + "<manifest><item id=\"one\" href=\"one.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
            + "<spine><itemref idref=\"one\"/></spine></package>";
        Object parsed = parsePackage(opf.getBytes(StandardCharsets.UTF_8));
        Object step = parsed.getClass().getMethod("getSpineStep").invoke(parsed);
        check(step.getClass().getMethod("getIndex").invoke(step).equals(4), "actual spine index");
        parsePackage(("<!DOCTYPE package>" + opf).getBytes(StandardCharsets.UTF_8));
        parsePackage(("<!DOCTYPE package>" + opf).getBytes(StandardCharsets.UTF_16));
        rejects(opf.substring(0, opf.length() - 10), "malformed XML");
        rejects("<!DOCTYPE package [<!ENTITY x \"value\">]>" + opf, "internal entity");
        rejects("<!DOCTYPE package SYSTEM \"file:///not-an-epub.dtd\">" + opf, "external DTD");
        rejects(opf.replace("http://www.idpf.org/2007/opf", "urn:other"), "wrong OPF namespace");
        String raw = "epubcfi(/6[reading-order]/2[ref-one]!/4,/2[first]/1:9,/4[escaped^,^;^=^[^]^^]/1:7)";
        Object position = cfi.getClass().getMethod("parse", String.class).invoke(cfi, raw);
        check(raw.equals(position.getClass().getMethod("serialize").invoke(position)), "raw range retention");
        System.out.println("BookOrbit parser smoke: " + checks + " checks passed");
    }

    private static Object parsePackage(byte[] bytes) throws Exception {
        Object result = epub.getClass().getMethod("parsePackage", String.class, byte[].class)
            .invoke(epub, "OPS/package.opf", bytes);
        checks++;
        return result;
    }

    private static void rejects(String xml, String label) throws Exception {
        try {
            parsePackage(xml.getBytes(StandardCharsets.UTF_8));
        } catch (InvocationTargetException error) {
            check(error.getCause().getClass().getName().endsWith("BookOrbitEpubPackage$ParseException"), label);
            return;
        }
        throw new AssertionError("Accepted " + label);
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
