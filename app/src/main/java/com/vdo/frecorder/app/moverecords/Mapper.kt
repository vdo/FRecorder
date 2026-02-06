package com.vdo.frecorder.app.moverecords

import com.vdo.frecorder.app.formatRecordInformation
import com.vdo.frecorder.app.settings.SettingsMapper
import com.vdo.frecorder.data.database.Record

fun recordToMoveRecordsItem(settingsMapper: SettingsMapper, item: Record): MoveRecordsItem {
	return MoveRecordsItem(
		item.id,
		item.name,
		formatRecordInformation(settingsMapper, item.format, item.sampleRate, item.size, item.bitrate)
	)
}

fun recordsToMoveRecordsItems(settingsMapper: SettingsMapper, items: List<Record>): List<MoveRecordsItem> {
	return items.map { recordToMoveRecordsItem(settingsMapper, it) }
}
