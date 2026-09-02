package net.gommagomma.stardust.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CollisionGrid {

    private final double cellSize;
    // Manteniamo la mappa ma riduciamo al minimo le ri-allocazioni
    private final Map<Long, List<Particle>> cells = new HashMap<>();

    public CollisionGrid(double cellSize) {
        this.cellSize = Math.max(cellSize, 1.0);
    }

    public double getCellSize() { return cellSize; }

    /**
     * Svuota la griglia per il nuovo step temporale senza distruggere
     * le liste già allocate nei bucket.
     */
    public void clear() {
        for (List<Particle> bucket : cells.values()) {
            bucket.clear();
        }
    }

    public void insert(Particle p) {
        if (p == null || !p.isAlive()) return;

        long key = keyOf(p.getPosition());
        cells.computeIfAbsent(key, k -> new ArrayList<>(8)).add(p);
    }

    /**
     * Popola la lista 'result' passata dal chiamante invece di crearne una nuova ogni volta.
     */
    public void queryNeighbors(Vector3D pos, double radius, List<Particle> result) {
        result.clear();

        int cellRadius = (int) Math.ceil(radius / cellSize);
        int cx = cellIndex(pos.getX());
        int cy = cellIndex(pos.getY());
        int cz = cellIndex(pos.getZ());

        for (int dx = -cellRadius; dx <= cellRadius; dx++) {
            for (int dy = -cellRadius; dy <= cellRadius; dy++) {
                for (int dz = -cellRadius; dz <= cellRadius; dz++) {
                    long key = pack(cx + dx, cy + dy, cz + dz);
                    List<Particle> bucket = cells.get(key);
                    if (bucket != null && !bucket.isEmpty()) {
                        result.addAll(bucket);
                    }
                }
            }
        }
    }

    private int cellIndex(double coord) {
        return (int) Math.floor(coord / cellSize);
    }

    private long keyOf(Vector3D pos) {
        return pack(cellIndex(pos.getX()), cellIndex(pos.getY()), cellIndex(pos.getZ()));
    }

    /**
     * Impacchetta 3 indici interi in un long (21 bit per asse).
     * Corretto l'overflow di promozione a long ed introdotto clamping di sicurezza.
     */
    private static long pack(int ix, int iy, int iz) {
        // Clamping nell'intervallo valido dei 21 bit firmati [-1048576, 1048575]
        ix = Math.max(-1048576, Math.min(1048575, ix));
        iy = Math.max(-1048576, Math.min(1048575, iy));
        iz = Math.max(-1048576, Math.min(1048575, iz));

        long x = ((long) ix + 1048576L) & 0x1FFFFFL;
        long y = ((long) iy + 1048576L) & 0x1FFFFFL;
        long z = ((long) iz + 1048576L) & 0x1FFFFFL;

        return (x << 42) | (y << 21) | z;
    }
}