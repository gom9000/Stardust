package net.gommagomma.stardust;

public class SimulationMetrics
{
	// Metriche di simulazione
    private double simulationTime;
    private long stepCount;
    
    // Metriche di evento
    private long totalMerges;
    private long totalBounces;
    private long totalFragmentations;
    private long totalEscapes;
    private long totalStarFalls;

    // Metriche di stabilità
    private double maxCourantObserved;


    // Costruttore per una nuova simulazione
    public SimulationMetrics() {
        this.simulationTime = 0.0;
        this.stepCount = 0;

        this.totalMerges = 0;
        this.totalBounces = 0;
        this.totalFragmentations = 0;
        this.totalEscapes = 0;
        this.totalStarFalls = 0;
 
        this.maxCourantObserved = 0.0;
    }


    // Costruttore di ripristino da savepoint
    public SimulationMetrics(double simulationTime, long stepCount, long totalMerges, long totalBounces, long totalFragmentations, long totalEscapes, long totalStarFalls) {
        this.simulationTime = simulationTime;
        this.stepCount = stepCount;

        this.totalMerges = totalMerges;
        this.totalBounces = totalBounces;
        this.totalFragmentations = totalFragmentations;
        this.totalEscapes = totalEscapes;
        this.totalStarFalls = totalStarFalls;
    }

    public synchronized void incrementStep() {
        stepCount++;
    }

    public synchronized void addTime(double dt) {
        simulationTime += dt;
    }

    public synchronized long recordMerge() { return ++totalMerges; }
    public synchronized long recordBounce() { return ++totalBounces; }
    public synchronized long recordFragmentation() { return ++totalFragmentations; }
    public synchronized long recordEscape() { return ++totalEscapes; }
    public synchronized long recordStarFall() { return ++totalStarFalls; }

    public synchronized void updateMaxCourant(double courant) {
        if (courant > maxCourantObserved) {
            maxCourantObserved = courant;
        }
    }

    public synchronized double getSimulationTime() { return simulationTime; }
    public synchronized long getStepCount() { return stepCount; }
    public synchronized long getTotalMerges() { return totalMerges; }
    public synchronized long getTotalBounces() { return totalBounces; }
    public synchronized long getTotalFragmentations() { return totalFragmentations; }
    public synchronized long getTotalEscapes() { return totalEscapes; }
    public synchronized long getTotalStarFalls() { return totalStarFalls; }
    public synchronized double getMaxCourantObserved() { return maxCourantObserved; }
}