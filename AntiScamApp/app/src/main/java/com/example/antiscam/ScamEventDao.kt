package com.example.antiscam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ScamEventDao {
    @Insert
    fun insert(event: ScamEvent)

    @Query("SELECT * FROM scam_events ORDER BY timestamp DESC")
    fun getAll(): List<ScamEvent>

    @Query("SELECT COUNT(*) FROM scam_events")
    fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM scam_events WHERE action = 'BLOCKED'")
    fun getBlockedCount(): Int

    @Query("SELECT AVG(riskScore) FROM scam_events")
    fun getAverageRisk(): Double?

    @Query("DELETE FROM scam_events")
    fun clearAll()
}
