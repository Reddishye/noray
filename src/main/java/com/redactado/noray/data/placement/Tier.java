package com.redactado.noray.data.placement;

/**
 * Storage class of a <em>piece of data</em> (a file, a folder policy, a range of chunks), not of a node.
 *
 * <ul>
 *   <li>{@link #HOT}: whole chunks (replicas) compressed with a fast codec: one fetch, cheap to decode.
 *       Placed preferring fast nodes (weighted by {@link StorageNode#speedWeight()}).</li>
 *   <li>{@link #COLD}: erasure-coded shards compressed with an aggressive codec: little space per tolerated
 *       loss. Placed preferring big nodes (weighted by {@link StorageNode#capacityWeight()}).</li>
 * </ul>
 * Every node can hold both classes, so all of them contribute bandwidth to hot data and capacity to cold
 * data; a node may opt out of a class with {@link StorageNode#accepts(Tier)}.
 * The {@code wireCode} is persisted in manifests: never change it.
 */
public enum Tier {
    HOT(1),
    COLD(2);

    private final int wireCode;

    Tier(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static Tier fromWireCode(int code) {
        for (Tier t : values()) {
            if (t.wireCode == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown tier code " + code);
    }
}
