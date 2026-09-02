package net.gommagomma.stardust.physics.gravity;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

public class GravityCalculator {

    // Metodo unificato richiamato dall'engine
    public static Vector3D calculateGravity(Particle p1, Particle p2, GravityModel model) {
        switch (model) {
            case NEWTONIAN_CLAMPED:
                return calculateClampedGravity(p1, p2);
            case PLUMMER_SOFTENED:
                return calculatePlummerGravity(p1, p2);
            default:
                throw new IllegalArgumentException("Unsupported gravity model: " + model);
        }
    }

    // Overload che legge direttamente dalla configurazione globale
    public static Vector3D calculateGravity(Particle p1, Particle p2) {
        return calculateGravity(p1, p2, SimulationConfig.ACTIVE_GRAVITY_MODEL);
    }

    /**
     * Forza gravitazionale newtoniana esercitata da p2 su p1.
     * Viene usato un clamping microscopico (1 cm²) per evitare divisioni per zero.
     */
    public static Vector3D calculateClampedGravity(Particle p1, Particle p2) {
        Vector3D diff = p2.getPosition().subtract(p1.getPosition());
        double distanceSquared = diff.magnitudeSquared();

        if (distanceSquared == 0) return new Vector3D(0, 0, 0);

        double safeDistSq = Math.max(distanceSquared, 1e-4);
        double forceFactor = (SimulationConfig.G * p1.getMass() * p2.getMass()) / (safeDistSq * Math.sqrt(safeDistSq));

        return diff.multiply(forceFactor);
    }

    /**
     * Forza gravitazionale con softening di Plummer esercitata da p2 su p1.
     * Questo metodo implementa la legge di gravitazione universale di Newton integrata con una tecnica
     * di regolarizzazione numerica nota come gravitational softening (ammorbidimento gravitazionale).
     */
    public static Vector3D calculatePlummerGravity(Particle p1, Particle p2) {
        Vector3D diff = p2.getPosition().subtract(p1.getPosition());
        double distanceSquared = diff.magnitudeSquared();

        if (distanceSquared == 0) return new Vector3D(0, 0, 0);

        double epsSq = SimulationConfig.SOFTENING * SimulationConfig.SOFTENING;
        double effectiveDistSq = distanceSquared + epsSq;
        double effectiveDist = Math.sqrt(effectiveDistSq);

        double forceFactor = (SimulationConfig.G * p1.getMass() * p2.getMass()) / (effectiveDistSq * effectiveDist);

        return diff.multiply(forceFactor);
    }
}