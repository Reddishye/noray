package com.redactado.noray.web.html;

import java.util.List;

/**
 * A piece of HTML built in code. There are three kinds and no raw-HTML escape hatch: every byte of output comes from a
 * validated element name, a validated attribute, or escaped text. Rendering is deterministic.
 */
public sealed interface Node permits Node.Element, Node.Text, Node.Fragment {

    /** Appends this node as HTML. */
    void render(StringBuilder out);

    default String html() {
        StringBuilder sb = new StringBuilder(256);
        render(sb);
        return sb.toString();
    }

    /** An element with validated name and attributes. Void elements ({@code br}, {@code img}...) never have children. */
    record Element(String tag, List<Attr> attrs, List<Node> children) implements Node {
        public Element {
            Safety.checkTag(tag);
            attrs = List.copyOf(attrs);
            children = List.copyOf(children);
            if (Safety.isVoid(tag) && !children.isEmpty()) {
                throw new IllegalArgumentException("<" + tag + "> cannot have children");
            }
        }

        @Override
        public void render(StringBuilder out) {
            out.append('<').append(tag);
            for (Attr a : attrs) {
                a.render(out);
            }
            out.append('>');
            if (Safety.isVoid(tag)) {
                return;
            }
            for (Node c : children) {
                c.render(out);
            }
            out.append("</").append(tag).append('>');
        }

        /** The value of an attribute, or null. */
        public String attr(String name) {
            for (Attr a : attrs) {
                if (a.name().equals(name)) return a.value();
            }
            return null;
        }
    }

    /** Text, always escaped. */
    record Text(String text) implements Node {
        public Text {
            text = text == null ? "" : text;
        }

        @Override
        public void render(StringBuilder out) {
            Safety.escapeText(text, out);
        }
    }

    /** Several nodes without a wrapper. */
    record Fragment(List<Node> children) implements Node {
        public Fragment {
            children = List.copyOf(children);
        }

        @Override
        public void render(StringBuilder out) {
            for (Node c : children) {
                c.render(out);
            }
        }
    }
}
