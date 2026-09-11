package net.gommagomma.stardust.physics.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.TestParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Verifica che le implementazioni della forza gravitazionale (clampata e Plummer-softened)
 * rispettino le formule chiuse attese, la terza legge di Newton, e i comportamenti limite
 * (softening, clamp, distanza nulla).
 *
 * GravityCalculator e' ora un'istanza costruita su un SimulationParams: ogni test usa
 * new GravityCalculator(params) con i valori di default di SimulationParams() (nessun file
 * coinvolto), cosi' i test restano riproducibili e isolati dal filesystem.
 */
class GravityCalculatorTest {

    private static final double REL_TOL = 1e-9;

    private static Particle particleAt(double x, double y, double z, double mass) {
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
        GravityCalculator gc = new GravityCalculator(TestParams.defaults());
        Particle p1 = particleAt(0, 0, 0, 5.0);
        Particle p2 = particleAt(10.0, 0, 0, 7.0);

        Vector3D force = gc.calculateClampedGravity(p1, p2);

        double expectedMagnitude = (PhysicsConstants.G * 5.0 * 7.0) / (10.0 * 10.0);
        assertEquals(expectedMagnitude, force.magnitude(), expectedMagnitude * REL_TOL,
                "Il modulo della forza deve coincidere con G*m1*m2/r^2");
        assertTrue(force.getX() > 0, "La forza su p1 deve essere attrattiva verso p2 (+x)");
        assertEquals(0.0, force.getY(), 1e-12);
        assertEquals(0.0, force.getZ(), 1e-12);
    }

    @Test
    void clampedGravity_respectsNewtonThirdLaw() {
        GravityCalculator gc = new GravityCalculator(TestParams.defaults());
        Particle p1 = particleAt(1.0, 2.0, 3.0, 4.0);
        Particle p2 = particleAt(-2.0, 0.5, 7.0, 9.0);

        Vector3D fOnP1 = gc.calculateClampedGravity(p1, p2);
        Vector3D fOnP2 = gc.calculateClampedGravity(p2, p1);

        assertVectorEquals(fOnP1, fOnP2.multiply(-1), REL_TOL,
                "Terza legge di Newton: F(p1<-p2) == -F(p2<-p1)");
    }

    @Test
    void clampedGravity_isZero_whenParticlesExactlyOverlap() {
        GravityCalculator gc = new GravityCalculator(TestParams.defaults());
        Particle p1 = particleAt(3.0, 3.0, 3.0, 1.0);
        Particle p2 = particleAt(3.0, 3.0, 3.0, 1.0);

        Vector3D force = gc.calculateClampedGravity(p1, p2);

        assertEquals(0.0, force.magnitude(), 0.0,
                "A distanza esattamente nulla il metodo ritorna vettore nullo (comportamento attuale, test di regressione).");
    }

    @Test
    void clampedGravity_neverDivergesBelowSafetyThreshold_andDecaysLinearlyToZero() {
        GravityCalculator gc = new GravityCalculator(TestParams.defaults());
        Particle p1 = particleAt(0, 0, 0, 1.0);
        Particle p2close = particleAt(0.001, 0, 0, 1.0);
        Particle p2atClamp = particleAt(0.01, 0, 0, 1.0);

        Vector3D fClose = gc.calculateClampedGravity(p1, p2close);
        Vector3D fAtClamp = gc.calculateClampedGravity(p1, p2atClamp);

        assertTrue(fClose.magnitude() < fAtClamp.magnitude(),
                "Sotto la soglia di clamp la forza deve calare (non esplodere) avvicinandosi ulteriormente");

        double expectedRatio = 0.001 / 0.01;
        double actualRatio = fClose.magnitude() / fAtClamp.magnitude();
        assertEquals(expectedRatio, actualRatio, expectedRatio * REL_TOL,
                "Sotto la soglia di clamp il modulo della forza deve scalare linearmente con la distanza reale");
    }

    @Test
    void plummerGravity_isFinite_atZeroDistance() {
        GravityCalculator gc = new GravityCalculator(TestParams.defaults());
        Particle p1 = particleAt(5.0, 5.0, 5.0, 2.0);
        Particle p2 = particleAt(5.0, 5.0, 5.0, 2.0);

        Vector3D force = gc.calculatePlummerGravity(p1, p2);

        assertEquals(0.0, force.magnitude(), 0.0,
                "Comportamento attuale: a distanza esattamente nulla il metodo ritorna vettore nullo, coerente con la versione clampata.");
    }

    @Test
    void plummerGravity_convergesToNewtonian_atDistanceMuchLargerThanSoftening() {
        SimulationParams params = TestParams.defaults(); // softening = 1.0 m
        GravityCalculator gc = new GravityCalculator(params);
        double r = 1000.0; // >> softening
        Particle p1 = particleAt(0, 0, 0, 6.0);
        Particle p2 = particleAt(r, 0, 0, 8.0);

        Vector3D plummerForce = gc.calculatePlummerGravity(p1, p2);
        double newtonianMagnitude = (PhysicsConstants.G * 6.0 * 8.0) / (r * r);

        double relDiff = Math.abs(plummerForce.magnitude() - newtonianMagnitude) / newtonianMagnitude;
        assertTrue(relDiff < 1e-4,
                "A r >> softening, Plummer deve convergere al valore Newtoniano puro (diff relativa: " + relDiff + ")");
    }

    @Test
    void plummerGravity_isWeakerThanNaiveNewtonian_nearSofteningScale() {
        SimulationParams params = TestParams.defaults();
        GravityCalculator gc = new GravityCalculator(params);
        double eps = params.softening;
        double r = eps / 4.0;
        Particle p1 = particleAt(0, 0, 0, 1.0);
        Particle p2 = particleAt(r, 0, 0, 1.0);

        Vector3D plummerForce = gc.calculatePlummerGravity(p1, p2);
        double naiveNewtonianMagnitude = (PhysicsConstants.G * 1.0 * 1.0) / (r * r);

        assertTrue(plummerForce.magnitude() < naiveNewtonianMagnitude,
                "Il softening di Plummer deve ridurre la forza rispetto alla singolarità Newtoniana pura a corto raggio");
    }

    @Test
    void calculateGravity_dispatchesToCorrectModel() {
        GravityCalculator gc = new GravityCalculator(TestParams.defaults());
        Particle p1 = particleAt(0, 0, 0, 3.0);
        Particle p2 = particleAt(50.0, 0, 0, 4.0);

        Vector3D viaClampedDispatch = gc.calculateGravity(p1, p2, GravityModel.NEWTONIAN_CLAMPED);
        Vector3D viaClampedDirect = gc.calculateClampedGravity(p1, p2);
        assertVectorEquals(viaClampedDirect, viaClampedDispatch, REL_TOL, "Dispatch NEWTONIAN_CLAMPED");

        Vector3D viaPlummerDispatch = gc.calculateGravity(p1, p2, GravityModel.PLUMMER_SOFTENED);
        Vector3D viaPlummerDirect = gc.calculatePlummerGravity(p1, p2);
        assertVectorEquals(viaPlummerDirect, viaPlummerDispatch, REL_TOL, "Dispatch PLUMMER_SOFTENED");
    }
}
