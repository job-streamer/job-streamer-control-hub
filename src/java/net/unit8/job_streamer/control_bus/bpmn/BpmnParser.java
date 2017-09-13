package net.unit8.job_streamer.control_bus.bpmn;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.ParseSettings;
import org.jsoup.parser.Parser;
import org.jsoup.parser.XmlTreeBuilder;
import org.jsoup.select.NodeVisitor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Parse BPMN file.
 *
 * @author kawasima
 */
public class BpmnParser {
    public BpmnParser() {

    }

    public Document parse(String xml) {
        Parser parser = new Parser(new XmlTreeBuilder())
                .settings(ParseSettings.preserveCase);
        Document doc = parser.parseInput(xml, "");

        final Map<String, Element> batchComponents = new HashMap<>();
        final Map<String, Element> transitions = new HashMap<>();
        final Map<String, Element> endEvents = new HashMap<>();
        final Map<String, List<String>> route = new HashMap<>();
        final Map<String, String> reverseRoute = new HashMap<>();
        final Map<String, Element> routeElements = new LinkedHashMap<>();

        doc.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                switch (node.nodeName()) {
                    case "jsr352:start":
                        routeElements.put(node.attr("id"), (Element) node);
                        break;
                    case "jsr352:step":
                    case "jsr352:flow":
                    case "jsr352:split":
                        routeElements.put(node.attr("id"), (Element) node);
                        batchComponents.put(node.attr("id"), (Element) node);
                        break;
                    case "jsr352:transition":
                        transitions.put(node.attr("id"), (Element) node);
                        String sourceRef = node.attr("sourceRef");
                        String targetRef = node.attr("targetRef");
                        List<String> target = route.get(sourceRef);
                        if (target == null) {
                            target = new ArrayList<>();
                            route.put(sourceRef, target);
                        }
                        target.add(targetRef);
                        reverseRoute.put(targetRef, sourceRef);
                        break;
                    case "jsr352:end":
                    case "jsr352:fail":
                    case "jsr352:stop":
                        routeElements.put(node.attr("id"), (Element) node);
                        endEvents.put(node.attr("id"), (Element) node);
                        break;
                    default:
                }
            }

            @Override
            public void tail(Node node, int depth) {

            }
        });

        SortedBpmnBuilder builder = new SortedBpmnBuilder(doc, routeElements, route, reverseRoute);
        Document sortedDoc = builder.build();
        Document root = new Document("");
        root.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        sortedDoc.traverse(new BpmnStructureVisitor(root, transitions, batchComponents, endEvents));
        return root;
    }
}
