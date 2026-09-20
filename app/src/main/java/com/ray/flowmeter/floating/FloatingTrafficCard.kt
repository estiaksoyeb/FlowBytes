package com.ray.flowmeter.floating

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ray.flowmeter.R

private val CardBackground = Color(0xF5131418)
private val CardBorder = Color(0x22FFFFFF)
private val ChipDownBg = Color(0x33FF9800)
private val ChipUpBg = Color(0x3300BCD4)
private val OrangeAccent = Color(0xFFFF9800)
private val CyanAccent = Color(0xFF00E5FF)
private val ProgressBarBg = Color(0x22FFFFFF)

val FLOATING_WINDOW_WIDTH = 240.dp

@Composable
fun FloatingTrafficCard(
    state: FloatingTrafficState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(FLOATING_WINDOW_WIDTH)
            .wrapContentHeight()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp)),
        color = CardBackground,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // Top Drag Handle Pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color(0x44FFFFFF))
                )
            }

            // Header Row: Minimal Title + Close Icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.floating_window_title),
                    color = Color(0xEEFFFFFF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color(0x99FFFFFF),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Speed Chips Row (Download & Upload)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                SpeedChip(
                    speedText = formatFloatingSpeed(state.rxSpeed),
                    isDownload = true,
                    modifier = Modifier.weight(1f)
                )

                SpeedChip(
                    speedText = formatFloatingSpeed(state.txSpeed),
                    isDownload = false,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            HorizontalDivider(
                color = Color(0x18FFFFFF),
                thickness = 0.5.dp
            )

            // Flexible container: only takes the exact space needed for active apps
            if (state.activeApps.isEmpty()) {
                Text(
                    text = stringResource(R.string.floating_window_no_traffic),
                    color = Color(0x55FFFFFF),
                    fontSize = 10.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp, bottom = 1.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    state.activeApps.take(3).forEach { app ->
                        AppTrafficRow(app = app)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedChip(
    speedText: String,
    isDownload: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isDownload) ChipDownBg else ChipUpBg
    val iconColor = if (isDownload) OrangeAccent else CyanAccent
    val arrowIcon = if (isDownload) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x2B1F222A))
            .padding(horizontal = 5.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = arrowIcon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(9.dp)
            )
        }

        Spacer(modifier = Modifier.width(5.dp))

        Text(
            text = speedText,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun AppTrafficRow(
    app: ActiveAppTraffic,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Icon
        if (app.icon != null) {
            Image(
                bitmap = app.icon,
                contentDescription = app.appName,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.appName.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Name + Speed + Progress Bar
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = app.appName,
                    color = Color(0xF0FFFFFF),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = formatFloatingSpeed(app.totalSpeed),
                    color = Color(0xD9FFFFFF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Dual-segment progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(ProgressBarBg)
            ) {
                val totalRatio = (app.rxRatio + app.txRatio).coerceIn(0.01f, 1f)
                val rxFraction = if (totalRatio > 0f) (app.rxRatio / totalRatio).coerceIn(0f, 1f) else 1f

                Row(
                    modifier = Modifier
                        .fillMaxWidth(totalRatio)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                ) {
                    if (app.rxRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(rxFraction.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(OrangeAccent)
                        )
                    }
                    if (app.txRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .weight((1f - rxFraction).coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(CyanAccent)
                        )
                    }
                }
            }
        }
    }
}
