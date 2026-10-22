package com.agenticengineering.urlshortener.orchestration.policy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * LIC-01 (all dependency licenses approved): every dependency in the license inventory (docs/compliance/licenses.xml)
 * must carry at least one license from the approved families - Apache-2.0, MIT, BSD, EPL, EDL, LGPL, MPL, CDDL or
 * GPLv2 with the classpath exception, public domain / CC0. Anything else, or no license, fails; an unreadable
 * inventory fails closed.
 */
public final class LicenseCheck {

    private static final List<String> APPROVED = List.of("apache", "mit", "bsd", "eclipse public", "epl", "eclipse distribution",
            "edl", "lesser general public", "lgpl", "mozilla public", "mpl", "cddl", "gpl2 w/ cpe", "classpath exception",
            "public domain", "cc0");

    private LicenseCheck() {
    }

    public static Evidence evaluate(Path inventory) {
        try {
            if (!Files.exists(inventory)) {
                return Evidence.notOk("LIC-01 inventory missing: " + inventory);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            NodeList dependencies = factory.newDocumentBuilder().parse(inventory.toFile()).getElementsByTagName("dependency");
            List<String> unapproved = new ArrayList<>();
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dependency = (Element) dependencies.item(i);
                NodeList names = dependency.getElementsByTagName("name");
                List<String> licenses = new ArrayList<>();
                boolean approved = false;
                for (int j = 0; j < names.getLength(); j++) {
                    String name = names.item(j).getTextContent().trim();
                    licenses.add(name);
                    String lower = name.toLowerCase(Locale.ROOT);
                    approved |= APPROVED.stream().anyMatch(lower::contains);
                }
                if (!approved) {
                    unapproved.add(text(dependency, "groupId") + ":" + text(dependency, "artifactId") + " " + licenses);
                }
            }
            return unapproved.isEmpty()
                    ? Evidence.ok(inventory + ": " + dependencies.getLength() + " dependencies, every license approved")
                    : Evidence.notOk(inventory + ": unapproved licenses: " + String.join("; ", unapproved));
        } catch (Exception e) {
            return Evidence.notOk("LIC-01 inventory unreadable (" + inventory + "): " + e.getClass().getSimpleName());
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
