package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.ui.Component;
import com.redactado.noray.web.ui.Render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.redactado.noray.web.html.Html.*;

/** Tabs; only the selected panel is rendered (its components keep their state while the page is open). */
public final class Tabs implements Component {

    private final Map<String, Component> panels = new LinkedHashMap<>();
    private String selected;

    public Tabs add(String label, Component panel) {
        panels.put(label, panel);
        if (selected == null) selected = label;
        return this;
    }

    @Override
    public Node render(Render r) {
        List<Node> tabs = new ArrayList<>();
        int i = 0;
        for (String label : panels.keySet()) {
            boolean on = label.equals(selected);
            tabs.add(button(type("button"), cls("nr-tab"), role("tab"), id("tab-" + i++), aria("selected", on),
                    r.onViewClick(() -> selected = label), label));
        }
        Component current = panels.get(selected);
        return div(div(cls("nr-tabs"), role("tablist"), tabs),
                div(role("tabpanel"), current == null ? null : r.live(current)));
    }
}
