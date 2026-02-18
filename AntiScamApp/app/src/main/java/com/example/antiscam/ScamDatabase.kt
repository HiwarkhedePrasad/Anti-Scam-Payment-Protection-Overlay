package com.example.antiscam

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ScamEvent::class], version = 1, exportSchema = false)
abstract class ScamDatabase : RoomDatabase() {
    abstract fun scamEventDao(): ScamEventDao

    companion object {
        @Volatile
        private var INSTANCE: ScamDatabase? = null

        fun getInstance(context: Context): ScamDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScamDatabase::class.java,
                    "antiscam_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
