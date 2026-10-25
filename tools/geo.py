"""WGS84 -> EarthLife block coordinates. Must match core/.../LocalProjection.java exactly (tested)."""
import math

A = 6378137.0
F = 1 / 298.257223563
E2 = F * (2 - F)


def ecef(lat, lon, h=0.0):
    la, lo = math.radians(lat), math.radians(lon)
    n = A / math.sqrt(1 - E2 * math.sin(la) ** 2)
    return ((n + h) * math.cos(la) * math.cos(lo), (n + h) * math.cos(la) * math.sin(lo), (n * (1 - E2) + h) * math.sin(la))


class Projection:
    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0
        la, lo = math.radians(lat0), math.radians(lon0)
        self.sl, self.cl, self.so, self.co = math.sin(la), math.cos(la), math.sin(lo), math.cos(lo)
        self.o = ecef(lat0, lon0, 0)

    def to_block(self, lat, lon):
        """Returns (x, z): x = metres east, z = metres south (Minecraft convention)."""
        x, y, z = ecef(lat, lon, 0)
        dx, dy, dz = x - self.o[0], y - self.o[1], z - self.o[2]
        east = -self.so * dx + self.co * dy
        north = -self.sl * self.co * dx - self.sl * self.so * dy + self.cl * dz
        return east, -north

    def from_block(self, x, z):
        """Inverse of to_block for surface points: returns (lat, lon). Iterates so the result sits on the ellipsoid."""
        import math as m
        east, north, up = x, -z, 0.0
        for _ in range(3):
            dx = -self.so * east - self.sl * self.co * north + self.cl * self.co * up
            dy = self.co * east - self.sl * self.so * north + self.cl * self.so * up
            dz = self.cl * north + self.sl * up
            X, Y, Z = self.o[0] + dx, self.o[1] + dy, self.o[2] + dz
            lon = m.atan2(Y, X)
            p = m.hypot(X, Y)
            lat = m.atan2(Z, p * (1 - E2))
            for _ in range(5):
                n = A / m.sqrt(1 - E2 * m.sin(lat) ** 2)
                h = p / m.cos(lat) - n
                lat = m.atan2(Z, p * (1 - E2 * n / (n + h)))
            up -= h  # move the ENU point down/up until height above ellipsoid is 0
        return m.degrees(lat), m.degrees(lon)
