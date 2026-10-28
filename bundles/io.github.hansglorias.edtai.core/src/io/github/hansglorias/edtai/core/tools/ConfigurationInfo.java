package io.github.hansglorias.edtai.core.tools;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Свойства конфигурации из {@code src/Configuration/Configuration.mdo}
 * проекта EDT. Пустая строка — свойства в файле нет.
 *
 * @param namePrefix      префикс имён расширения; у основной конфигурации пусто
 * @param extensionPurpose назначение расширения (Customization, AddOn, Patch); у основной конфигурации пусто
 */
public record ConfigurationInfo(String name, String synonym, String version, String vendor, String compatibilityMode,
        String scriptVariant, String namePrefix, String extensionPurpose) {

    public boolean isExtension() {
        return !namePrefix.isEmpty() || !extensionPurpose.isEmpty();
    }

    /**
     * @throws IOException если это не XML
     */
    public static ConfigurationInfo parse(String xml) throws IOException {
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Файл из проекта: внешние сущности и DOCTYPE не нужны и не разрешаются.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Configuration.mdo не читается как XML: " + e.getMessage(), e);
        }
        return new ConfigurationInfo(child(root, "name"), synonym(root), child(root, "version"), child(root, "vendor"),
                child(root, "compatibilityMode"), child(root, "scriptVariant"), child(root, "namePrefix"),
                child(root, "configurationExtensionPurpose"));
    }

    /** Строки для модели: «Имя: Демо», «Синоним: …» — только заполненные. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        add(lines, isExtension() ? "Расширение" : "Конфигурация", name);
        add(lines, "Синоним", synonym);
        add(lines, "Версия", version);
        add(lines, "Поставщик", vendor);
        add(lines, "Режим совместимости", compatibilityMode);
        add(lines, "Вариант встроенного языка", scriptVariant);
        add(lines, "Префикс имён расширения", namePrefix);
        add(lines, "Назначение расширения", extensionPurpose);
        return lines;
    }

    private static void add(List<String> lines, String label, String value) {
        if (!value.isEmpty()) {
            lines.add(label + ": " + value);
        }
    }

    /** Синоним по-русски, а если его нет — первый. */
    private static String synonym(Element root) {
        String first = "";
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && "synonym".equals(element.getLocalName())) {
                String value = child(element, "value");
                if ("ru".equals(child(element, "key"))) {
                    return value;
                }
                if (first.isEmpty()) {
                    first = value;
                }
            }
        }
        return first;
    }

    /** Текст первого дочернего элемента с этим именем, без учёта пространства имён. */
    private static String child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                return element.getTextContent().strip();
            }
        }
        return "";
    }
}
