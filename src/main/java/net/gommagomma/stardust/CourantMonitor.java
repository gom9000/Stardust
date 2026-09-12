package net.gommagomma.stardust;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Oggetto di appoggio condiviso: il massimo numero di Courant PREVENTIVO osservato nello step
 * corrente, calcolato durante la fase di calcolo delle forze -- PRIMA dell'integrazione, a
 * differenza del Courant "a posteriori" già presente in processCollision (calcolato dopo
 * l'integrazione, sulle nuove posizioni, quando il passo è già stato fatto).
 *
 * Scritto da molti thread in parallelo (computeForcesParallel/computeForcesBarnesHut girano su
 * IntStream.parallel()), letto da uno solo (SimulationEngine, dopo la fase di forze). Nessun
 * synchronized, nessuna struttura dati nuova da costruire o interrogare: un singolo valore
 * atomico aggiornato via compare-and-swap, che vive accanto al ciclo che già calcola le forze
 * invece che in un componente separato.
 */
public class CourantMonitor {

    private final AtomicLong maxBits = new AtomicLong(Double.doubleToLongBits(0.0));

    public void reset() {
        maxBits.set(Double.doubleToLongBits(0.0));
    }

    /** Aggiorna il massimo in modo lock-free; sicuro da chiamare da più thread contemporaneamente. */
    public void update(double candidate) {
        long candidateBits = Double.doubleToLongBits(candidate);
        long current;
        do {
            current = maxBits.get();
            if (candidate <= Double.longBitsToDouble(current)) return;
        } while (!maxBits.compareAndSet(current, candidateBits));
    }

    public double getMax() {
        return Double.longBitsToDouble(maxBits.get());
    }
}
