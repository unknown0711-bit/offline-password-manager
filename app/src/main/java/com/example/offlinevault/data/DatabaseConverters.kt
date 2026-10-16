package com.example.offlinevault.data

import androidx.room.TypeConverter

/** Room adapters for the stable database representations of enum metadata. */
class DatabaseConverters {
    @TypeConverter
    fun entryTypeToDatabaseValue(value: EntryType): String = value.databaseValue

    @TypeConverter
    fun databaseValueToEntryType(value: String): EntryType =
        EntryType.values().firstOrNull { it.databaseValue == value }
            ?: error("Unknown vault entry type: $value")

    @TypeConverter
    fun fieldKindToDatabaseValue(value: FieldKind): String = value.databaseValue

    @TypeConverter
    fun databaseValueToFieldKind(value: String): FieldKind =
        FieldKind.values().firstOrNull { it.databaseValue == value }
            ?: error("Unknown entry field kind: $value")
}
