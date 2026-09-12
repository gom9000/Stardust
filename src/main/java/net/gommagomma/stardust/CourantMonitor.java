package net.gommagomma.stardust;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Oggetto di appoggio condiviso, raggiungibile da ogni parte del motore che ne abbia bisogno,
 * per lo stato del timestep adattivo di questo step:
 *
 *   - il massimo numero di Courant PREVENTIVO osservato nello step corrente, insieme agli ID
 *     della coppia di particelle che l'ha generato
 *
 *   - il dt NOMINALE (quello configurato, prima di un'eventuale riduzione), salvato qui da
 *     SimulationEngine.step() apposta per poter ripristinare params.dt a fine step. Non serve
 *     alcuna sincronizzazione: viene scritto e letto solo dal thread che orchestra lo step
 *     (mai dai thread di lavoro paralleli), in due punti sequenziali ben definiti.
 */
public class CourantMonitor {

    /** Istantanea immutabile: valore e coppia di ID scambiati sempre insieme, mai separatamente. */
    private static final class Event {
        final double courant;
        final int id1;
        final int id2;

        Event(double courant, int id1, int id2) {
            this.courant = courant;
            this.id1 = id1;
            this.id2 = id2;
        }
    }

    private static final Event ZERO = new Event(0.0, -1, -1);

    private final AtomicReference<Event> current = new AtomicReference<>(ZERO);
    private double nominalDt;

    public void reset() {
        current.set(ZERO);
    }

    /**
     * Aggiorna il massimo in modo lock-free; sicuro da chiamare da più thread contemporaneamente.
     * id1/id2 sono gli ID delle due particelle coinvolte -- registrati SOLO se questo candidato
     * risulta essere il nuovo massimo, atomicamente insieme al valore.
     */
    public void update(double candidate, int id1, int id2) {
        Event snapshot;
        do {
            snapshot = current.get();
            if (candidate <= snapshot.courant) return; // uscita rapida, nessuna allocazione nel caso comune
        } while (!current.compareAndSet(snapshot, new Event(candidate, id1, id2)));
    }

    public double getMax() {
        return current.get().courant;
    }

    /** ID della prima particella della coppia che ha generato il massimo corrente, o -1 se nessuna. */
    public int getMaxPairId1() {
        return current.get().id1;
    }

    /** ID della seconda particella della coppia che ha generato il massimo corrente, o -1 se nessuna. */
    public int getMaxPairId2() {
        return current.get().id2;
    }

    /** Salva il dt configurato prima di un'eventuale riduzione */
    public void setNominalDt(double dt) {
        this.nominalDt = dt;
    }

    public double getNominalDt() {
        return nominalDt;
    }

    //
    // Stato AGGREGATO dall'ultimo summary periodico -- distinto dallo stato per-step sopra.
    // Toccato SOLO dal thread che orchestra step() (mai dai thread paralleli delle forze), quindi
    // campi semplici, nessuna atomicità necessaria. Serve a non perdere la notizia di una
    // riduzione sostenuta tra un summary e l'altro: il valore per-step da solo è una fotografia di
    // un singolo istante a caso, che può benissimo cadere proprio nel momento in cui il Courant è
    // sotto soglia anche in mezzo a un episodio di riduzione lungo migliaia di step.
    //

    private int reductionsSinceLastSummary = 0;
    private double worstEffectiveDtSinceLastSummary = Double.POSITIVE_INFINITY;
    private double worstCourantSinceLastSummary = 0.0;
    private int worstPairId1SinceLastSummary = -1;
    private int worstPairId2SinceLastSummary = -1;

    /** Chiamato da step() ogni volta che dt viene ridotto in questo step (mai negli step normali). */
    public void recordReduction(double effectiveDt, double courant, int id1, int id2) {
        reductionsSinceLastSummary++;
        if (courant > worstCourantSinceLastSummary) {
            worstCourantSinceLastSummary = courant;
            worstEffectiveDtSinceLastSummary = effectiveDt;
            worstPairId1SinceLastSummary = id1;
            worstPairId2SinceLastSummary = id2;
        }
    }

    public int getReductionsSinceLastSummary() {
        return reductionsSinceLastSummary;
    }

    public double getWorstEffectiveDtSinceLastSummary() {
        return worstEffectiveDtSinceLastSummary;
    }

    public double getWorstCourantSinceLastSummary() {
        return worstCourantSinceLastSummary;
    }

    public int getWorstPairId1SinceLastSummary() {
        return worstPairId1SinceLastSummary;
    }

    public int getWorstPairId2SinceLastSummary() {
        return worstPairId2SinceLastSummary;
    }

    /** Chiamato da step() subito dopo aver stampato il summary periodico: azzera la finestra
     *  aggregata per il prossimo intervallo, senza toccare lo stato per-step (che segue il suo
     *  ciclo di reset ad ogni singolo step, indipendentemente da questo). */
    public void resetSummaryWindow() {
        reductionsSinceLastSummary = 0;
        worstEffectiveDtSinceLastSummary = Double.POSITIVE_INFINITY;
        worstCourantSinceLastSummary = 0.0;
        worstPairId1SinceLastSummary = -1;
        worstPairId2SinceLastSummary = -1;
    }
}
