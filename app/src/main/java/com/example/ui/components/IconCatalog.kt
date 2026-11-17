package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * Catálogo central de íconos Material 3 y componentes reutilizables (Esqueletos de carga
 * y Estados vacíos con ilustración).
 */
object IconCatalog {

    fun iconForName(iconName: String): ImageVector = when (iconName.lowercase()) {
        "payments" -> Icons.Default.Payments
        "account_balance" -> Icons.Default.AccountBalance
        "smartphone" -> Icons.Default.Smartphone
        "restaurant" -> Icons.Default.Restaurant
        "directions_bus" -> Icons.Default.DirectionsBus
        "home" -> Icons.Default.Home
        "bolt" -> Icons.Default.Bolt
        "shopping_cart" -> Icons.Default.ShoppingCart
        "medical_services" -> Icons.Default.MedicalServices
        "movie" -> Icons.Default.Movie
        "checkroom" -> Icons.Default.Checkroom
        "local_gas_station" -> Icons.Default.LocalGasStation
        "credit_card" -> Icons.Default.CreditCard
        "storefront" -> Icons.Default.Storefront
        "work" -> Icons.Default.Work
        "store" -> Icons.Default.Store
        "sell" -> Icons.Default.Sell
        "add_card" -> Icons.Default.AddCard
        "savings" -> Icons.Default.Savings
        "flight" -> Icons.Default.Flight
        "transfer" -> Icons.Default.SwapHoriz
        "wallet" -> Icons.Default.AccountBalanceWallet
        else -> Icons.Default.Category
    }
}

/**
 * Ícono circular con fondo tintado para cuentas y categorías.
 */
@Composable
fun CategoryOrAccountBadge(
    iconName: String,
    colorHex: Long,
    size: Dp = 42.dp,
    contentDescription: String? = null
) {
    val baseColor = Color(colorHex)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(baseColor.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = IconCatalog.iconForName(iconName),
            contentDescription = contentDescription,
            tint = baseColor,
            modifier = Modifier.size(size * 0.54f)
        )
    }
}

/**
 * Esqueleto animado (Shimmer) para estados de carga de Gemini y tarjetas.
 */
@Composable
fun ShimmerSkeletonBox(
    modifier: Modifier = Modifier,
    height: Dp = 72.dp
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translateAnim - 300f, 0f),
        end = Offset(translateAnim, 200f)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
    )
}

/**
 * Estado vacío con la ilustración generada para MiPlata, título claro y botón de acción.
 */
@Composable
fun IllustratedEmptyState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_finance_1794276865197),
            contentDescription = title,
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .height(140.dp)
                .clip(RoundedCornerShape(20.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onActionClick,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = actionLabel)
            }
        }
    }
}
