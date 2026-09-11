package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.TestParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Test di integrazione "leggero": un singolo planetesimo in orbita circolare attorno alla stella
 * centrale, integrato con l'Euler-Cromer reale (Particle.update) e la forza reale
 * (Physics.calculateCentralStarGravity), SENZA passare per SimulationEngine.
 */
class TwoBodyOrbitIntegrationTest {

    private final SimulationParams params = TestParams.defaults();
    private final Physics physics = new Physics(params);

    private void stepUnderStarGravity(Particle p, double dt) {
        p.resetForce();
        p.addForce(physics.calculateCentralStarGravity(p));
        p.update(dt);
    }

    private double totalEnergy(Particle p) {
        double v = p.getVelocity().magnitude();
        double kinetic = 0.5 * p.getMass() * v * v;
        double potential = physics.calculateCentralStarPotentialEnergy(p);
        return kinetic + potential;
    }

    private static double angularMomentumZ(Particle p) {
        Vector3D r = p.getPosition();
        Vector3D v = p.getVelocity();
        return p.getMass() * (r.getX() * v.getY() - r.getY() * v.getX());
    }

    @Test
    void circularOrbit_conservesEnergyAndAngularMomentum_overOneFullPeriod() {
        double r0 = PhysicsConstants.AU;
        double vCirc = Math.sqrt((PhysicsConstants.G * params.centralStarMass) / r0);

        Particle planetesimal = new Particle(
                new Vector3D(r0, 0, 0),
                new Vector3D(0, vCirc, 0),
                1.0e20,
                0.0,
                3000.0
        );

        double period = 2.0 * Math.PI * Math.sqrt(Math.pow(r0, 3) / (PhysicsConstants.G * params.centralStarMass));
        double dt = params.dt;
        int steps = (int) Math.round(period / dt);

        double energyInitial = totalEnergy(planetesimal);
        double angularMomentumInitial = angularMomentumZ(planetesimal);

        double maxEnergyDrift = 0.0;
        double maxAngularMomentumDrift = 0.0;

        for (int i = 0; i < steps; i++) {
            stepUnderStarGravity(planetesimal, dt);

            double energyDrift = Math.abs((totalEnergy(planetesimal) - energyInitial) / energyInitial);
            double angMomDrift = Math.abs((angularMomentumZ(planetesimal) - angularMomentumInitial) / angularMomentumInitial);

            maxEnergyDrift = Math.max(maxEnergyDrift, energyDrift);
            maxAngularMomentumDrift = Math.max(maxAngularMomentumDrift, angMomDrift);
        }

        assertTrue(maxEnergyDrift < 0.01,
                "L'energia totale non deve derivare più dell'1% durante un'orbita completa (drift osservato: "
                        + (maxEnergyDrift * 100) + "%)");
        assertTrue(maxAngularMomentumDrift < 0.01,
                "Il momento angolare non deve derivare più dell'1% durante un'orbita completa (drift osservato: "
                        + (maxAngularMomentumDrift * 100) + "%)");

        double finalDistanceFromStart = planetesimal.getPosition().distanceTo(new Vector3D(r0, 0, 0));
        assertTrue(finalDistanceFromStart < 0.05 * r0,
                "Dopo un periodo orbitale completo la particella deve tornare vicino al punto di partenza "
                        + "(distanza residua: " + finalDistanceFromStart + " m, soglia: " + (0.05 * r0) + " m)");
    }

    @Test
    void radialInfall_particleFallsInward_whenStartedAtRestOffCenter() {
        double r0 = PhysicsConstants.AU;
        Particle p = new Particle(new Vector3D(r0, 0, 0), new Vector3D(0, 0, 0), 1.0e20, 0.0, 3000.0);

        double dt = params.dt;
        for (int i = 0; i < 50; i++) {
            stepUnderStarGravity(p, dt);
        }

        double distanceAfter = p.getPosition().magnitude();
        assertTrue(distanceAfter < r0,
                "Una particella ferma deve avvicinarsi alla stella (caduta radiale), non allontanarsi");
    }
}
