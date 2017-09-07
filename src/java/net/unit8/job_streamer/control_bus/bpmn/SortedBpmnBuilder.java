package net.unit8.job_streamer.control_bus.bpmn;

import org.jsoup.nodes.*;
import org.jsoup.select.Elements;

import java.util.*;

public class SortedBpmnBuilder {
    private Element document;
    private Map<String, Integer> order;

    public SortedBpmnBuilder(Document document, Map<String, Element> routeElements, Map<String, List<String>> route, Map<String, String> reverseRoute) {
        this.document = document;
        this.order = makeOrder(routeElements, route, reverseRoute);
    }

    private Map<String, Integer> makeOrder(Map<String, Element> routeElements, Map<String, List<String>> route, Map<String, String> reverseRoute) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Set<String> ids = routeElements.keySet();
        for (String id : ids) {
            Set<String> loopCheck = new HashSet<>();
            String routeStartId = getRouteStartId(id, route, reverseRoute, loopCheck);
            if (routeStartId != null) {
                addRouteOrder(result, routeStartId, route);
            }
        }
        for (String id : ids) {
            if (result.containsKey(id))
                continue;
            result.put(id, result.size());
        }
        return result;
    }

    private String getRouteStartId(String id, Map<String, List<String>> route, Map<String, String> reverseRoute, Set<String> loopCheck) {
        loopCheck.add(id);
        String prevId = reverseRoute.get(id);
        if (loopCheck.contains(prevId))
            return id;//loop, stop searching.
        List<String> nextIds = route.get(id);
        if (nextIds == null && prevId == null)
            return null;//no connection
        if (prevId == null)
            return id;//start
        return getRouteStartId(prevId, route, reverseRoute, loopCheck);
    }

    private void addRouteOrder(Map<String, Integer> result, String id, Map<String, List<String>> route) {
        if (result.containsKey(id))
            return;//already added
        result.put(id, result.size());
        List<String> nextIds = route.get(id);
        if (nextIds == null)
            return;//no next point
        for (String nextId : nextIds) {
            addRouteOrder(result, nextId, route);
        }
    }

    public Document build() {
        Document result = new Document("");
        doBuild(document, result);
        return result;
    }

    private void doBuild(Element element, Element result) {
        Elements children = element.children();
        Collections.sort(children, new BpmnNodeComparator(order));
        for (Element child : children) {
            Element copy = shallowCopy(child);
            doBuild(child, copy);
            result.appendChild(copy);
        }
    }

    private Element shallowCopy(Element src) {
        Element result = new Element(src.tagName());
        Attributes attributes = src.attributes();
        for (Attribute attribute : attributes) {
            result.attr(attribute.getKey(), attribute.getValue());
        }
        if(src.ownText().length() != 0)
            result.text(src.ownText());
        return result;
    }

    private static class BpmnNodeComparator implements Comparator<Node> {
        private final Map<String, Integer> order;
        public BpmnNodeComparator(Map<String, Integer> order) {
            this.order = order;
        }

        @Override
        public int compare(Node o1, Node o2) {
            Integer order1 = order.get(o1.attr("id"));
            Integer order2 = order.get(o2.attr("id"));
            if (order1 == null && order2 == null)
                return 0;
            if (order1 == null)
                return -1;
            if (order2 == null)
                return 1;
            return (order1.compareTo(order2));
        }
    }
}
