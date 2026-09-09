package net.gommagomma.stardust.physics.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Verifica che le implementazioni della forza gravitazionale (clampata e Plummer-softened)
 * rispettino le formule chiuse attese, la terza legge di Newton, e i comportamenti limite
 * (softening, clamp, distanza nulla).
 *
 * Tutti i test usano masse/distanze "di laboratorio" scelte solo per rendere i numeri
 * leggibili: le formule sono scala-invarianti, quindi la correttezza verificata qui vale
 * anche alle masse/distanze reali della simulazione (AU, masse di planetesimi, ecc.).
 */
class GravityCalculatorTest {

    private static final double REL_TOL = 1e-9; // tolleranza relativa per confronti in doppia precisione

    private static Particle particleAt(double x, double y, double z, double mass) {
        // Densità arbitraria: non entra nel calcolo della forza gravitazionale, serve solo al costruttore.
        return new Particle(new Vector3D(x, y, z), new Vector3D(0, 0, 0), mass, 0.0, 3000.0);
    }

    private static void assertVectorEquals(Vector3D expected, Vector3D actual, double relTol, String msg) {
        double scale = Math.max(1.0, expected.magnitude());
        assertEquals(expected.getX(), actual.getX(), scale * relTol, msg + " (x)");
        assertEquals(expected.getY(), actual.getY(), scale * relTol, msg + " (y)");
        assertEquals(expected.getZ(), actual.getZ(), scale * relTol, msg + " (z)");
    }

    @Test
    void clampedGravity_matchesNewtonianFormula_atLargeDistance() {
        // A distanza ben oltre la soglia di clamp (1 cm), la forza deve coincidere
        // con G*m1*m2/r^2 diretta lungo la congiungente.
        Particle p1 = particleAt(0, 0, 0, 5.0);
        Particle p2 = particleAt(10.0, 0, 0, 7.0);

        Vector3D force = GravityCalculator.calculateClampedGravity(p1, p2);

        double expectedMagnitude = (SimulationConfig.G * 5.0 * 7.0) / (10.0 * 10.0);
        assertEquals(expectedMagnitude, force.magnitude(), expectedMagnitude * REL_TOL,
                "Il modulo della forza deve coincidere con G*m1*m2/r^2");

        // La forza su p1 deve puntare verso p2, cioè lungo +x in questo caso.
        assertTrue(force.getX() > 0, "La forza su p1 deve essere attrattiva verso p2 (+x)");
        assertEquals(0.0, force.getY(), 1e-12);
        assertEquals(0.0, force.getZ(), 1e-12);
    }

    @Test
    void clampedGravity_respectsNewtonThirdLaw() {
        // F(p1 <- p2) deve essere esattamente l'opposto di F(p2 <- p1): stessa retta, versi opposti.
        Particle p1 = particleAt(1.0, 2.0, 3.0, 4.0);
        Particle p2 = particleAt(-2.0, 0.5, 7.0, 9.0);

        Vector3D fOnP1 = GravityCalculator.calculateClampedGravity(p1, p2);
        Vector3D fOnP2 = GravityCalculator.calculateClampedGravity(p2, p1);

        assertVectorEquals(fOnP1, fOnP2.multiply(-1), REL_TOL,
                "Terza legge di Newton: F(p1<-p2) == -F(p2<-p1)");
    }

    @Test
    void clampedGravity_isZero_whenParticlesExactlyOverlap() {
        Particle p1 = particleAt(3.0, 3.0, 3.0, 1.0);
        Particle p2 = particleAt(3.0, 3.0, 3.0, 1.0);

        Vector3D force = GravityCalculator.calculateClampedGravity(p1, p2);

        assertEquals(0.0, force.magnitude(), 0.0,
                "A distanza esattamente nulla il metodo ritorna vettore nullo (comportamento attuale, " +
                "documentato qui come test di regressione: si noti che è leggermente incoerente col fatto " +
                "che a distanza 0.5 cm invece il clamp restituirebbe una forza finita non nulla).");
    }

    @Test
    void clampedGravity_neverDivergesBelowSafetyThreshold_andDecaysLinearlyToZero() {
        // IMPORTANTE (verificato empiricamente contro l'implementazione, non assunto): il "clamp" non
        // produce un plateau. Il denominatore (safeDistSq) viene effettivamente congelato a 1e-4 m^2 sotto
        // la soglia di 1 cm, MA il vettore ritornato è ancora diff.multiply(forceFactor), dove diff ha il
        // modulo della distanza REALE (non clampata). Quindi, sotto la soglia, il modulo della forza non
        // resta costante: decresce LINEARMENTE con la distanza reale (perché il denominatore è fisso),
        // andando a zero quando i due corpi si sovrappongono esattamente. Il risultato pratico voluto è
        // comunque raggiunto: niente più singolarità 1/r^2 vicino a r=0, la forza è sempre finita e cala.
        Particle p1 = particleAt(0, 0, 0, 1.0);
        Particle p2close = particleAt(0.001, 0, 0, 1.0);   // 1 mm: sotto la soglia di clamp
        Particle p2atClamp = particleAt(0.01, 0, 0, 1.0);  // 1 cm: esattamente alla soglia (denominatore identico)

        Vector3D fClose = GravityCalculator.calculateClampedGravity(p1, p2close);
        Vector3D fAtClamp = GravityCalculator.calculateClampedGravity(p1, p2atClamp);

        // Nessuna divergenza: avvicinandosi ulteriormente sotto la soglia la forza NON deve aumentare.
        assertTrue(fClose.magnitude() < fAtClamp.magnitude(),
                "Sotto la soglia di clamp la forza deve calare (non esplodere) avvicinandosi ulteriormente");

        // Con denominatore congelato uguale per entrambi i punti (stesso safeDistSq=1e-4), il modulo
        // scala esattamente in proporzione alla distanza reale: f(0.001)/f(0.01) == 0.001/0.01 == 0.1.
        double expectedRatio = 0.001 / 0.01;
        double actualRatio = fClose.magnitude() / fAtClamp.magnitude();
        assertEquals(expectedRatio, actualRatio, expectedRatio * REL_TOL,
                "Sotto la soglia di clamp il modulo della forza deve scalare linearmente con la distanza reale");
    }

    @Test
    void plummerGravity_isFinite_atZeroDistance() {
        Particle p1 = particleAt(5.0, 5.0, 5.0, 2.0);
        Particle p2 = particleAt(5.0, 5.0, 5.0, 2.0);

        Vector3D force = GravityCalculator.calculatePlummerGravity(p1, p2);

        assertEquals(0.0, force.magnitude(), 0.0,
                "Comportamento attuale: a distanza esattamente nulla il metodo esce prima di applicare " +
                "il softening e ritorna vettore nullo, coerentemente con la versione clampata.");
    }

    @Test
    void plummerGravity_convergesToNewtonian_atDistanceMuchLargerThanSoftening() {
        // Con SOFTENING piccolo (1 m in SimulationConfig) e distanza su scala di AU, il softening
        // deve diventare trascurabile: Plummer e Newtoniano puro devono coincidere.
        double r = 1000.0; // >> SOFTENING
        Particle p1 = particleAt(0, 0, 0, 6.0);
        Particle p2 = particleAt(r, 0, 0, 8.0);

        Vector3D plummerForce = GravityCalculator.calculatePlummerGravity(p1, p2);
        double newtonianMagnitude = (SimulationConfig.G * 6.0 * 8.0) / (r * r);

        double relDiff = Math.abs(plummerForce.magnitude() - newtonianMagnitude) / newtonianMagnitude;
        assertTrue(relDiff < 1e-4,
                "A r >> softening, Plummer deve convergere al valore Newtoniano puro (diff relativa: " + relDiff + ")");
    }

    @Test
    void plummerGravity_isWeakerThanNaiveNewtonian_nearSofteningScale() {
        // Vicino alla scala del softening, il potenziale di Plummer deve SMORZARE la forza
        // rispetto alla formula Newtoniana pura (che qui, non essendo clampata, diverge):
        // è esattamente lo scopo del softening, evitare la singolarità a corto raggio.
        double eps = SimulationConfig.SOFTENING;
        double r = eps / 4.0; // molto più vicino della scala di softening
        Particle p1 = particleAt(0, 0, 0, 1.0);
        Particle p2 = particleAt(r, 0, 0, 1.0);

        Vector3D plummerForce = GravityCalculator.calculatePlummerGravity(p1, p2);
        double naiveNewtonianMagnitude = (SimulationConfig.G * 1.0 * 1.0) / (r * r);

        assertTrue(plummerForce.magnitude() < naiveNewtonianMagnitude,
                "Il softening di Plummer deve ridurre la forza rispetto alla singolarità Newtoniana pura a corto raggio");
    }

    @Test
    void calculateGravity_dispatchesToCorrectModel() {
        Particle p1 = particleAt(0, 0, 0, 3.0);
        Particle p2 = particleAt(50.0, 0, 0, 4.0);

        Vector3D viaClampedDispatch = GravityCalculator.calculateGravity(p1, p2, GravityModel.NEWTONIAN_CLAMPED);
        Vector3D viaClampedDirect = GravityCalculator.calculateClampedGravity(p1, p2);
        assertVectorEquals(viaClampedDirect, viaClampedDispatch, REL_TOL, "Dispatch NEWTONIAN_CLAMPED");

        Vector3D viaPlummerDispatch = GravityCalculator.calculateGravity(p1, p2, GravityModel.PLUMMER_SOFTENED);
        Vector3D viaPlummerDirect = GravityCalculator.calculatePlummerGravity(p1, p2);
        assertVectorEquals(viaPlummerDirect, viaPlummerDispatch, REL_TOL, "Dispatch PLUMMER_SOFTENED");
    }
}
