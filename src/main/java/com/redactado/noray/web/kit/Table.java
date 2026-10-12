package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.ui.Component;
import com.redactado.noray.web.ui.Render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.redactado.noray.web.html.Html.*;

/**
 * A data table with columns from lambdas, server-side sorting, filtering and pagination. Rows come from a supplier,
 * called at every render, so the table shows current data whenever its region is refreshed.
 *
 * <pre>{@code
 * Table<Volume> t = Table.of("Volumes", volumes::list)
 *         .column("Id", v -> mono(v.id()))
 *         .column("State", v -> badge(v.state().name(), tone(v)), Comparator.comparing(Volume::state))
 *         .numeric("Size", v -> bytes(v.size()), Comparator.comparingLong(Volume::size))
 *         .filter((v, q) -> v.id().contains(q))
 *         .pageSize(25);
 * }</pre>
 */
public final class Table<T> implements Component {

    private record Column<T>(String header, Function<T, Object> cell, Comparator<T> order, boolean numeric) {
    }

    private final String caption;
    private final Supplier<? extends List<T>> rows;
    private final List<Column<T>> columns = new ArrayList<>();
    private BiPredicate<T, String> filter;
    private int pageSize = 50;
    private String emptyText = "Nothing to show.";

    // state, per open page
    private int sortColumn = -1;
    private boolean ascending = true;
    private int page;
    private String query = "";

    private Table(String caption, Supplier<? extends List<T>> rows) {
        this.caption = caption;
        this.rows = rows;
    }

    public static <T> Table<T> of(String caption, Supplier<? extends List<T>> rows) {
        return new Table<>(caption, rows);
    }

    public Table<T> column(String header, Function<T, Object> cell) {
        columns.add(new Column<>(header, cell, null, false));
        return this;
    }

    public Table<T> column(String header, Function<T, Object> cell, Comparator<T> order) {
        columns.add(new Column<>(header, cell, order, false));
        return this;
    }

    /** A right-aligned column with tabular figures. */
    public Table<T> numeric(String header, Function<T, Object> cell, Comparator<T> order) {
        columns.add(new Column<>(header, cell, order, true));
        return this;
    }

    /** Enables the search box; the predicate gets each row and the lower-cased query. */
    public Table<T> filter(BiPredicate<T, String> predicate) {
        this.filter = predicate;
        return this;
    }

    public Table<T> pageSize(int n) {
        this.pageSize = Math.max(1, n);
        return this;
    }

    public Table<T> emptyText(String text) {
        this.emptyText = text;
        return this;
    }

    /** Sorts initially by a column. */
    public Table<T> sortedBy(int column, boolean asc) {
        this.sortColumn = column;
        this.ascending = asc;
        return this;
    }

    @Override
    public Node render(Render r) {
        List<T> source = rows.get();
        List<T> all = new ArrayList<>(source);
        if (filter != null && !query.isBlank()) {
            String q = query.toLowerCase(Locale.ROOT).strip();
            all.removeIf(t -> !filter.test(t, q));
        }
        if (sortColumn >= 0 && sortColumn < columns.size() && columns.get(sortColumn).order() != null) {
            Comparator<T> c = columns.get(sortColumn).order();
            all.sort(ascending ? c : c.reversed());
        }
        int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pages - 1);
        List<T> shown = all.subList(page * pageSize, Math.min(all.size(), (page + 1) * pageSize));

        List<Node> head = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Column<T> col = columns.get(i);
            int index = i;
            Object label = col.order() == null ? col.header() : button(type("button"), cls("nr-sort"),
                    sortColumn == i ? aria("sort", ascending ? "ascending" : "descending") : null,
                    r.onViewClick(() -> {
                        if (sortColumn == index) ascending = !ascending; else { sortColumn = index; ascending = true; }
                    }), col.header());
            head.add(th(scope("col"), col.numeric() ? cls("nr-r") : null, label));
        }
        List<Node> body = new ArrayList<>();
        for (T t : shown) {
            List<Node> cells = new ArrayList<>();
            for (Column<T> col : columns) {
                Object v = col.cell().apply(t);
                cells.add(td(col.numeric() ? cls("nr-r") : null, v instanceof Node n ? n : text(v)));
            }
            body.add(tr(cells));
        }
        Node search = filter == null ? null : div(cls("nr-pager"),
                label(cls("nr-field"), span(cls("nr-muted"), "Filter " + caption.toLowerCase(Locale.ROOT)),
                        input(type("search"), value(query), placeholder("Type to filter"), r.onView("input", e -> {
                            query = e.value();
                            page = 0;
                        }))),
                span(all.size() + " of " + source.size()));
        Node pager = Kit.pagination(r, page, pages, p -> page = p);
        return div(search,
                div(cls("nr-table-wrap"),
                        table(cls("nr-table"), caption(cls("nr-visually-hidden"), caption), thead(tr(head)),
                                tbody(body.isEmpty() ? tr(td(colspan(columns.size()), div(cls("nr-empty"), emptyText))) : body))),
                pager);
    }
}
