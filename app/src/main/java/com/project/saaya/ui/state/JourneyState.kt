package com.project.saaya.ui.state

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Train
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.android.gms.maps.model.LatLng
import java.util.Locale

enum class TransportMode(
    val title: String,
    val icon: ImageVector,
    val routesMode: String
) {
    WALKING("Walking", Icons.AutoMirrored.Outlined.DirectionsWalk, "WALK"),
    CAB("Cab / Auto", Icons.Outlined.DirectionsCar, "DRIVE"),
    TWO_WHEELER("2-Wheeler", Icons.AutoMirrored.Outlined.DirectionsBike, "TWO_WHEELER"),
    CAR("Car", Icons.Outlined.DirectionsCar, "DRIVE"),
    BUS("Bus", Icons.Outlined.DirectionsBus, "TRANSIT"),
    METRO("Metro / Train", Icons.Outlined.Train, "TRANSIT")
}

data class RouteInfo(
    val durationSeconds: Long,
    val distanceMeters: Int,
    val polyline: List<LatLng>
) {
    val etaText: String
        get() {
            val minutes = ((durationSeconds + 59) / 60)
            return if (minutes < 60) "$minutes min"
            else {
                val hours = minutes / 60
                val remaining = minutes % 60
                if (remaining == 0L) "$hours hr" else "$hours hr $remaining min"
            }
        }

    val distanceText: String
        get() {
            val km = distanceMeters / 1000.0
            return if (km < 1.0) "$distanceMeters m"
            else String.format(Locale.US, "%.1f km", km)
        }
}
