package net.gommagomma.stardust;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Oggetto di appoggio condiviso, raggiungibile da ogni parte del motore che ne abbia bisogno,
 * per lo stato del timestep adattivo di questo step:
 *
 *   - il massimo numero di Courant PREVENTIVO osservato nello step corrente, calcolato durante
 *     il calcolo delle forze -- PRIMA dell'integrazione, a differenza del Courant "a posteriori"
 *     che si otteneva solo dopo l'integrazione, sulle nuove posizioni, quando il passo era già
 *     stato fatto. Scritto da molti thread in parallelo (computeForcesParallel/computeForcesBarnesHut
 *     girano su IntStream.parallel()), letto da uno solo (SimulationEngine, dopo la fase di forze).
 *     Nessun synchronized: un singolo valore atomico aggiornato via compare-and-swap.
 *
 *   - il dt NOMINALE (quello configurato, prima di un'eventuale riduzione), salvato qui da
 *     SimulationEngine.step() apposta per poter ripristinare params.dt a fine step. Non serve
 *     alcuna sincronizzazione: viene scritto e letto solo dal thread che orchestra lo step
 *     (mai dai thread di lavoro paralleli), in due punti sequenziali ben definiti.
 */
public class CourantMonitor {

    private final AtomicLong maxBits = new AtomicLong(Double.doubleToLongBits(0.0));
    private double nominalDt;

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

    /** Salva il dt configurato prima di un'eventuale riduzione -- solo il thread orchestratore
     *  dello step scrive e legge questo valore, mai i thread di lavoro paralleli. */
    public void setNominalDt(double dt) {
        this.nominalDt = dt;
    }

    public double getNominalDt() {
        return nominalDt;
    }
}
