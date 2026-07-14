package com.filestech.agenda_tech.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.filestech.agenda_tech.domain.model.CalendarColor

// Indexed on source_id: looked up on every (re-)import to reuse the calendar of a device source.
@Entity(tableName = "calendars", indices = [Index(value = ["source_id"])])
data class CalendarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    // Stored as INTEGER via AgendaEnumConverters (CalendarColor.rawValue).
    @ColumnInfo(name = "color") val color: CalendarColor,
    @ColumnInfo(name = "visible") val visible: Boolean,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    // Stable link to an imported source (e.g. "device:6"); null for user-created calendars.
    @ColumnInfo(name = "source_id") val sourceId: String? = null,
)
