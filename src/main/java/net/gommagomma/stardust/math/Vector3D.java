package net.gommagomma.stardust.math;


/**
 * Vettore 3D immutabile: rappresenta posizioni, velocità e forze.
 */
public class Vector3D
{
    private final double x, y, z;

    public Vector3D(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }

    public Vector3D add(Vector3D other) {
        return new Vector3D(x + other.x, y + other.y, z + other.z);
    }

    public Vector3D subtract(Vector3D other) {
        return new Vector3D(x - other.x, y - other.y, z - other.z);
    }

    public Vector3D multiply(double scalar) {
        return new Vector3D(x * scalar, y * scalar, z * scalar);
    }

    public Vector3D divide(double scalar) {
        return new Vector3D(x / scalar, y / scalar, z / scalar);
    }

    public double magnitudeSquared() {
        return x * x + y * y + z * z;
    }

    public double magnitude() {
        return Math.sqrt(magnitudeSquared());
    }

    public double distanceTo(Vector3D other) {
        return this.subtract(other).magnitude();
    }

    public double dotProduct(Vector3D other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }
    
    // Versore (vettore unitario) nella stessa direzione. Ritorna il vettore nullo se magnitude è 0.
    public Vector3D normalize() {
        double mag = magnitude();
        if (mag == 0) return new Vector3D(0, 0, 0);
        return divide(mag);
    }

    @Override
    public String toString() {
        return String.format("(%.3e, %.3e, %.3e)", x, y, z);
    }
}
