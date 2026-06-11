// XML parsing CPU hot-spot: repeated DOM parsing of a large XML document.
// xml-parser-N threads spin in javax.xml — visible in stack as DocumentBuilder.
import javax.xml.parsers.*;
import org.xml.sax.*;
import java.io.*;
public class XmlParsingCpu {
    static final String XML;
    static {
        var sb = new StringBuilder("<root>");
        for (int i = 0; i < 2000; i++) sb.append("<item id=\"").append(i).append("\">value</item>");
        sb.append("</root>");
        XML = sb.toString();
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    var factory = DocumentBuilderFactory.newInstance();
                    while (!Thread.currentThread().isInterrupted()) {
                        factory.newDocumentBuilder().parse(
                            new InputSource(new StringReader(XML)));
                    }
                } catch (Exception e) {}
            }, "xml-parser-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
