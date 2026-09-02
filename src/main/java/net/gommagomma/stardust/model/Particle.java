package net.gommagomma.stardust.model;

import java.util.concurrent.atomic.AtomicInteger;

import net.gommagomma.stardust.math.Vector3D;

public class Particle
{
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);
    private final int id; // identificatore univoco
    private boolean alive = true;

    // Proprietà fisiche
    private double mass;           // kg
    private double charge;         // Coulomb (positiva o negativa)
    private double initialRadius;  // metri (calcolato dalla densità)
    private double radius;         // metri (calcolato dalla densità)
    private double density;        // kg/m^3 (es. 100 per polvere soffice, 3000 per roccia)
    private int mergerCount = 0;   // quante volte questo corpo si è fuso con altri

    private double potentialEnergy = 0.0;
    
    // Proprietà cinematiche (vettori 3D)
    private Vector3D position;
    private Vector3D velocity;
    private Vector3D force;
    private Vector3D acceleration;


    /**
     * Costruttore di una nuova particella.
     * @param position
     * @param velocity
     * @param mass
     * @param charge
     * @param density
     */
    public Particle(Vector3D position, Vector3D velocity, double mass, double charge, double density)
    {
        this.id = ID_COUNTER.getAndIncrement();

        this.position = position;
        this.velocity = velocity;
        this.force = new Vector3D(0, 0, 0);
        this.acceleration = new Vector3D(0, 0, 0);

        this.mass = mass;
        this.charge = charge;
        this.density = density;
        updateRadius();
        this.initialRadius = radius;
    }


    /**
     * Costruttore di ripristino usato per ricreare una particella da savepoint.
     * 
     * @param id
     * @param position
     * @param velocity
     * @param mass
     * @param charge
     * @param density
     * @param initialRadius
     * @param mergerCount
     */
    public Particle(int id, Vector3D position, Vector3D velocity, double mass, double charge, double density, double initialRadius, int mergerCount)
    {
        this.id = id;
        this.position = position;
        this.velocity = velocity;
        this.mass = mass;
        this.charge = charge;
        this.density = density;
        this.force = new Vector3D(0, 0, 0);
        this.acceleration = new Vector3D(0, 0, 0);
        updateRadius();
        this.initialRadius = initialRadius;
        this.mergerCount = mergerCount;
    }
 
    
    public void resetPotentialEnergy() {
        this.potentialEnergy = 0.0;
    }

    public void addPotentialEnergy(double energy) {
        this.potentialEnergy += energy;
    }

    public double getPotentialEnergy() {
        return this.potentialEnergy;
    }
    

    // Da chiamare dopo aver ripristinato un checkpoint, cosi' che eventuali NUOVE
    // particelle create in seguito non riusino id gia' presenti nel file caricato.
    public static void ensureIdCounterAtLeast(int minNextId)
    {
        ID_COUNTER.updateAndGet(current -> Math.max(current, minNextId));
    }


    /**
     * Raggio derivato dalla massa e dalla densità: V = (4/3) * pi * r^3.
     */
    public void updateRadius()
    {
        double volume = this.mass / this.density;
        this.radius = Math.cbrt((3.0 * volume) / (4.0 * Math.PI));
    }


    // Accumula le forze
    public void addForce(Vector3D f)
    {
        this.force = this.force.add(f);
    }


    // Azzera la forza
    public void resetForce()
    {
        this.force = new Vector3D(0, 0, 0);
    }

    // Avanzamento cinematico (Euler-Cromer)
    public void update(double dt)
    {
        // a = F / m
        this.acceleration = this.force.divide(this.mass);
        
        // v = v + a * dt
        this.velocity = this.velocity.add(acceleration.multiply(dt));
        
        // p = p + v * dt
        this.position = this.position.add(this.velocity.multiply(dt));
    }


    // Getter/Setter
    public int getId() { return id; }
    public double getMass() { return mass; }
    public void setMass(double mass) { this.mass = mass; }
    public double getCharge() { return charge; }
    public void setCharge(double charge) { this.charge = charge; }
    public double getRadius() { return radius; }
    public double getInitialRadius() { return initialRadius; }
    public double getDensity() { return density; }
    public void setDensity(double density) { this.density = density; }
    public Vector3D getPosition() { return position; }
    public void setPosition(Vector3D position) { this.position = position; }
    public Vector3D getVelocity() { return velocity; }
    public void setVelocity(Vector3D velocity) { this.velocity = velocity; }
    public Vector3D getForce() { return force; }
    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    public int getMergerCount() { return mergerCount; }
    public void incrementMergerCount(int added) { this.mergerCount += added; }
    public boolean isAggregated() { return mergerCount > 0; }
    public Vector3D getAcceleration() { return acceleration; }
    public void setAcceleration(Vector3D acceleration) { this.acceleration = acceleration; }
}
