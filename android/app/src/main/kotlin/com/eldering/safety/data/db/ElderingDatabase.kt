package com.eldering.safety.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * On-device database. Everything here stays on the phone (backups are disabled in the manifest).
 *
 * Schema changes need a Room migration: never add `fallbackToDestructiveMigration`, it would silently delete the
 * emergency contacts and pending incident uploads. Exported schemas live in `app/schemas/`.
 */
@Database(
    entities = [
        TrailPointEntity::class,
        DetectionEventRecord::class,
        EmergencyContact::class,
        OwnIncident::class,
        OutboxItem::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ElderingDatabase : RoomDatabase() {
    abstract fun trailDao(): TrailDao
    abstract fun detectionEventDao(): DetectionEventDao
    abstract fun emergencyContactDao(): EmergencyContactDao
    abstract fun ownIncidentDao(): OwnIncidentDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "eldering.db"

        fun build(context: Context): ElderingDatabase =
            Room.databaseBuilder(context.applicationContext, ElderingDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

class Converters {
    @TypeConverter
    fun featuresToJson(features: Map<String, Float>): String = json.encodeToString(featuresSerializer, features)

    @TypeConverter
    fun featuresFromJson(value: String): Map<String, Float> = json.decodeFromString(featuresSerializer, value)

    private companion object {
        val featuresSerializer = MapSerializer(String.serializer(), Float.serializer())

        /** Detector features may contain NaN/Infinity (e.g. no gyro); keep them rather than crash on insert. */
        val json = Json { allowSpecialFloatingPointValues = true }
    }
}
