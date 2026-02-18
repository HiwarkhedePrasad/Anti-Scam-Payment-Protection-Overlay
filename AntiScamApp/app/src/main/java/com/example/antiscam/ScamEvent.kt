package com.example.antiscam

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scam_events")
data class ScamEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String,
    val appName: String,
    val riskScore: Double,
    val textSnippet: String,     // first 200 chars of detected text
    val action: String           // "BLOCKED" or "DISMISSED"
)
