package net.gommagomma.stardust.physics.barneshut;

import java.util.ArrayList;
import java.util.List;

import net.gommagomma.stardust.CourantMonitor;
import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.Physics;

public class BarnesHutTree {
    private static final int MAX_DEPTH = 40;

    private final SimulationParams params;
    private final Physics physics;
    private final Node root;
    private final double theta;
    private final double thetaSq;

    public BarnesHutTree(List<Particle> particles, SimulationParams params, Physics physics) {
        if (particles == null || particles.isEmpty()) {
            throw new IllegalArgumentException("BarnesHutTree richiede almeno una particella");
        }

        this.params = params;
        this.physics = physics;
        this.theta = params.barnesHutTheta;
        this.thetaSq = theta * theta;

        double[] bounds = computeBounds(particles);

        this.root = new Node(bounds[0], bounds[1], bounds[2], bounds[3], params, physics);

        for (Particle p : particles) {
            if (p != null && p.isAlive()) {
                root.insert(p, 0);
            }
        }

        root.computeAggregates();
    }

    /** Firma invariata: tutti i chiamanti esistenti (test compresi) continuano a funzionare senza
     *  modifiche -- il Courant preventivo è opzionale, non un requisito imposto a chi non lo usa. */
    public Vector3D computeForce(Particle p) {
        return computeForce(p, null);
    }

    public Vector3D computeForce(Particle p, CourantMonitor courantMonitor) {
        if (p == null || !p.isAlive()) {
            return new Vector3D(0, 0, 0);
        }

        p.resetPotentialEnergy();
        ForceAccumulator acc = new ForceAccumulator();
        double targetReach = physics.getCaptureReach(p); // calcolato UNA VOLTA per bersaglio, non per ogni foglia visitata

        root.accumulateForce(p, thetaSq, acc, true, targetReach);

        p.addPotentialEnergy(acc.potentialEnergy);

        if (courantMonitor != null) {
            courantMonitor.update(acc.maxCourant);
        }

        return new Vector3D(acc.fx, acc.fy, acc.fz);
    }

    private double[] computeBounds(List<Particle> particles) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;

        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        boolean found = false;

        for (Particle p : particles) {
            if (p == null || !p.isAlive()) {
                continue;
            }

            Vector3D pos = p.getPosition();

            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());

            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());

            found = true;
        }

        if (!found) {
            throw new IllegalArgumentException("Nessuna particella viva nel Barnes-Hut tree");
        }

        double cx = 0.5 * (minX + maxX);
        double cy = 0.5 * (minY + maxY);
        double cz = 0.5 * (minZ + maxZ);

        double spanX = maxX - minX;
        double spanY = maxY - minY;
        double spanZ = maxZ - minZ;

        double halfSize = 0.5 * Math.max(Math.max(spanX, spanY), spanZ);

        halfSize = Math.max(halfSize, 1.0);
        halfSize *= 1.001;

        return new double[] { cx, cy, cz, halfSize };
    }

    private static class ForceAccumulator {

        double fx = 0.0;
        double fy = 0.0;
        double fz = 0.0;
        double potentialEnergy = 0.0;
        double maxCourant = 0.0;

        void add(double x, double y, double z) {
            this.fx += x;
            this.fy += y;
            this.fz += z;
        }

        void addPotential(double p) {
            this.potentialEnergy += p;
        }

        void updateMaxCourant(double candidate) {
            if (candidate > maxCourant) maxCourant = candidate;
        }
    }

    private static class Node {
    	private final SimulationParams params;
    	private final Physics physics;
        final double cx;
        final double cy;
        final double cz;
        final double halfSize;

        Node[] children;

        Particle single;
        List<Particle> overflow;

        double totalMass;
        double totalCharge;

        double comX;
        double comY;
        double comZ;

        Node(double cx, double cy, double cz, double halfSize, SimulationParams params, Physics physics) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.halfSize = halfSize;
            this.params = params;
            this.physics = physics;
        }

        boolean isLeaf() {
            return children == null;
        }

        void insert(Particle p, int depth) {
            if (p == null || !p.isAlive()) {
                return;
            }

            if (isLeaf()) {
                if (overflow != null) {
                    overflow.add(p);
                    return;
                }

                if (single == null) {
                    single = p;
                    return;
                }

                if (depth >= MAX_DEPTH) {
                    overflow = new ArrayList<>(2);
                    overflow.add(single);
                    overflow.add(p);
                    single = null;
                    return;
                }

                subdivide();

                Particle existing = single;
                single = null;

                insertIntoChild(existing, depth);
                insertIntoChild(p, depth);

                return;
            }

            insertIntoChild(p, depth);
        }

        private void subdivide() {
            children = new Node[8];
            double q = halfSize * 0.5;

            for (int i = 0; i < 8; i++) {
                double ox = ((i & 1) == 0) ? -q : q;
                double oy = ((i & 2) == 0) ? -q : q;
                double oz = ((i & 4) == 0) ? -q : q;

                children[i] = new Node(cx + ox, cy + oy, cz + oz, q, params, physics);
            }
        }

        private void insertIntoChild(Particle p, int depth) {
            children[octantOf(p)].insert(p, depth + 1);
        }

        private int octantOf(Particle p) {
            Vector3D pos = p.getPosition();
            int idx = 0;

            if (pos.getX() >= cx) { idx |= 1; }
            if (pos.getY() >= cy) { idx |= 2; }
            if (pos.getZ() >= cz) { idx |= 4; }

            return idx;
        }

        void computeAggregates() {
            totalMass = 0.0;
            totalCharge = 0.0;

            double weightedX = 0.0;
            double weightedY = 0.0;
            double weightedZ = 0.0;

            if (isLeaf()) {
                if (overflow != null) {
                    for (Particle p : overflow) {
                        if (p == null || !p.isAlive()) { continue; }

                        double m = p.getMass();
                        Vector3D pos = p.getPosition();

                        totalMass += m;
                        if (params.enableElectrostaticForce) {
                            totalCharge += p.getCharge();
                        }

                        weightedX += pos.getX() * m;
                        weightedY += pos.getY() * m;
                        weightedZ += pos.getZ() * m;
                    }
                } else if (single != null && single.isAlive()) {
                    double m = single.getMass();
                    Vector3D pos = single.getPosition();

                    totalMass = m;
                    if (params.enableElectrostaticForce) {
                        totalCharge = single.getCharge();
                    }

                    weightedX = pos.getX() * m;
                    weightedY = pos.getY() * m;
                    weightedZ = pos.getZ() * m;
                }
            } else {
                for (Node child : children) {
                    child.computeAggregates();

                    totalMass += child.totalMass;
                    totalCharge += child.totalCharge;

                    weightedX += child.comX * child.totalMass;
                    weightedY += child.comY * child.totalMass;
                    weightedZ += child.comZ * child.totalMass;
                }
            }

            if (totalMass > 0.0) {
                comX = weightedX / totalMass;
                comY = weightedY / totalMass;
                comZ = weightedZ / totalMass;
            } else {
                comX = cx;
                comY = cy;
                comZ = cz;
            }
        }

        void accumulateForce(Particle target, double thetaSq, ForceAccumulator acc, boolean containsTarget, double targetReach) {
            if (totalMass == 0.0 && (!params.enableElectrostaticForce || totalCharge == 0.0)) {
                return;
            }

            if (isLeaf()) {
                if (single != null) {
                    if (single != target && single.isAlive()) {
                        addDirect(target, single, acc, targetReach);
                    }
                } else if (overflow != null) {
                    for (Particle other : overflow) {
                        if (other != null && other != target && other.isAlive()) {
                            addDirect(target, other, acc, targetReach);
                        }
                    }
                }
                return;
            }

            Vector3D pos = target.getPosition();
            double targetX = pos.getX();
            double targetY = pos.getY();
            double targetZ = pos.getZ();

            double dx = comX - targetX;
            double dy = comY - targetY;
            double dz = comZ - targetZ;

            double distSq = dx * dx + dy * dy + dz * dz;
            double size = halfSize * 2.0;

            if (!containsTarget && distSq > 0.0 && (size * size / distSq) < thetaSq) {
                double softeningSq = params.softening * params.softening;
                double effectiveDistSq = distSq + softeningSq;
                double effectiveDist = Math.sqrt(effectiveDistSq);

                double gFactor = (PhysicsConstants.G * target.getMass() * totalMass) / (effectiveDistSq * effectiveDist);
                double cFactor = 0.0;

                if (params.enableElectrostaticForce) {
                    cFactor = (PhysicsConstants.K_COULOMB * target.getCharge() * totalCharge) / (effectiveDistSq * effectiveDist);
                }

                double netFactor = gFactor - cFactor;
                acc.add(dx * netFactor, dy * netFactor, dz * netFactor);

                double gPotential = -(PhysicsConstants.G * target.getMass() * totalMass) / effectiveDist;
                double cPotential = 0.0;

                if (params.enableElectrostaticForce) {
                    cPotential = (PhysicsConstants.K_COULOMB * target.getCharge() * totalCharge) / effectiveDist;
                }

                acc.addPotential(gPotential + cPotential);
                return;
            }

            int targetOctant = -1;
            if (containsTarget) {
                targetOctant = 0;
                if (targetX >= cx) { targetOctant |= 1; }
                if (targetY >= cy) { targetOctant |= 2; }
                if (targetZ >= cz) { targetOctant |= 4; }
            }

            for (int i = 0; i < children.length; i++) {
                Node child = children[i];

                if (child.totalMass == 0.0 && (!params.enableElectrostaticForce || child.totalCharge == 0.0)) {
                    continue;
                }

                boolean childContainsTarget = containsTarget && i == targetOctant;
                child.accumulateForce(target, thetaSq, acc, childContainsTarget, targetReach);
            }
        }

        private void addDirect(Particle target, Particle other, ForceAccumulator acc, double targetReach) {
            Vector3D f = physics.calculateGravityAndElectrostaticForce(target, other);
            acc.add(f.getX(), f.getY(), f.getZ());

            double dist = target.getPosition().distanceTo(other.getPosition());
            if (dist > 0) {
                double gPotential = -(PhysicsConstants.G * target.getMass() * other.getMass()) / dist;
                double cPotential = 0.0;

                if (params.enableElectrostaticForce) {
                    cPotential = (PhysicsConstants.K_COULOMB * target.getCharge() * other.getCharge()) / dist;
                }

                acc.addPotential(gPotential + cPotential);
            }

            // Courant PREVENTIVO: questa è l'unica sede dove esiste una coppia ESATTA (non
            // approssimata come nodo lontano) -- esattamente le coppie che contano per un
            // controllo di prossimità, calcolato qui a costo pressoché nullo perché la distanza
            // e le posizioni sono già in mano per il calcolo della forza appena fatto sopra.
            // targetReach e' precalcolato una sola volta per bersaglio (vedi computeForce):
            // solo other.getCaptureReach() viene ancora calcolato qui, una chiamata invece di due.
            double sumRadii = target.getRadius() + other.getRadius();
            if (sumRadii > 0 && dist <= targetReach + physics.getCaptureReach(other)) {
                double relSpeed = target.getVelocity().subtract(other.getVelocity()).magnitude();
                acc.updateMaxCourant((relSpeed * params.dt) / sumRadii);
            }
        }
    }
}