package com.tamaqai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local recovery marker only; this is not a cloud diary or a session credential. */
@Entity(tableName = "local_links")
data class LocalLinkEntity(@PrimaryKey val operation: String, val owner: String)
