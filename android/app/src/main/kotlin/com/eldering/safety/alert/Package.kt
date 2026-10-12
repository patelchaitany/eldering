/*
 * Accident handling: AlertController (freeze trail, full-screen countdown) and IncidentSender
 * (POST /v1/incidents, SMS to emergency contacts, live trail updates, WorkManager retries).
 * Test safety: SMS goes through an interface; debug builds dry-run unless "Allow real SMS" is enabled,
 * and nothing here ever places a call (ACTION_DIAL only).
 */
package com.eldering.safety.alert
