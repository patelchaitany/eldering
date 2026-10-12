package com.eldering.safety.testing

/**
 * Response bodies exactly as the Go server encodes them (server/internal/handlers): snake_case keys, RFC 3339
 * times with nanoseconds, explicit nulls for nil pointers, omitted `omitempty` fields, embedded structs flattened.
 */
object ServerSamples {
    const val SETTINGS = """{"sharing_state":"paused","paused_until":null,"always_alert_on_accident":true,"effective_sharing_state":"paused"}"""

    const val FAMILY = """{"id":"$FAMILY_ID","name":"Rivera family","role":"admin","joined_at":"2026-10-12T21:04:05.123456Z","member_count":3,"created_at":"2026-10-12T21:04:05.123456Z"}"""

    const val ME = """
        {"user":{"id":"$USER_ID","email":"asha@example.com","display_name":"Asha","created_at":"2026-10-01T08:00:00Z"},
         "settings":$SETTINGS,
         "families":[$FAMILY]}
    """

    const val CREATE_FAMILY = """
        {"id":"$FAMILY_ID","name":"Rivera family","role":"admin","joined_at":"2026-10-13T08:00:00.5Z","member_count":1,
         "created_at":"2026-10-13T08:00:00.5Z","invite":{"code":"7KQ2M9XD","expires_at":"2026-10-15T08:00:00.5Z","max_uses":10}}
    """

    const val INVITE = """{"code":"H3J8W0QZ","expires_at":"2026-10-15T08:00:00Z","max_uses":10}"""

    const val MEMBERS = """
        [{"user_id":"$USER_ID","display_name":"Asha","role":"admin","joined_at":"2026-10-12T21:04:05Z","is_me":true,"my_grant":null,"their_grant":null},
         {"user_id":"$OTHER_USER_ID","display_name":"Ravi","role":"member","joined_at":"2026-10-12T22:00:00Z","is_me":false,"my_grant":"emergency","their_grant":"live"}]
    """

    const val GRANT = """{"viewer_id":"$OTHER_USER_ID","display_name":"Ravi","level":"live","is_default":false,"updated_at":"2026-10-13T08:01:00Z"}"""

    const val GRANTS = """[{"viewer_id":"$OTHER_USER_ID","display_name":"Ravi","level":"emergency","is_default":true,"updated_at":null}]"""

    const val INCIDENT_SUMMARY = """
        {"id":"$INCIDENT_ID","user_id":"$OTHER_USER_ID","display_name":"Ravi","kind":"pedestrian_impact","status":"active",
         "detected_at":"2026-10-13T07:58:00Z","lat":48.137154,"lng":11.576124,
         "last_location":{"lat":48.1372,"lng":11.5762,"recorded_at":"2026-10-13T07:59:45Z","accuracy_m":6.5},
         "share_expires_at":"2026-10-16T07:58:01Z","resolved_at":null,"resolution":null,"is_mine":false,"my_ack":null}
    """

    const val HEARTBEAT = """{"pending_alerts":[$INCIDENT_SUMMARY],"server_time":"2026-10-13T08:00:00.250Z"}"""

    const val FAMILY_LOCATIONS = """
        [{"user_id":"$OTHER_USER_ID","display_name":"Ravi","status":"live","lat":48.1372,"lng":11.5762,"accuracy_m":12,
          "speed_mps":1.4,"bearing_deg":90,"activity":"walking","battery_pct":76,"recorded_at":"2026-10-13T07:59:30Z"},
         {"user_id":"2a3b4c5d-6e7f-4801-9a2b-3c4d5e6f7a8b","display_name":"Meera","status":"hidden"},
         {"user_id":"3a3b4c5d-6e7f-4801-9a2b-3c4d5e6f7a8b","display_name":"Dev","status":"paused"}]
    """

    const val CREATE_INCIDENT = """{"id":"$INCIDENT_ID","status":"active","share_url":"https://safety.example/s/abcDEF123_-xyz","share_expires_at":"2026-10-16T08:00:00Z"}"""

    const val ACK = """{"incident_id":"$INCIDENT_ID","user_id":"$USER_ID","display_name":"Asha","state":"on_my_way","at":"2026-10-13T08:00:05Z"}"""

    const val INCIDENT_DETAIL = """
        {"id":"$INCIDENT_ID","user_id":"$OTHER_USER_ID","display_name":"Ravi","kind":"fall","status":"resolved",
         "detected_at":"2026-10-13T07:58:00+00:00","lat":null,"lng":null,"last_location":null,
         "share_expires_at":"2026-10-16T07:58:01Z","resolved_at":"2026-10-13T08:10:00Z","resolution":"handled","is_mine":false,"my_ack":"seen",
         "details":{"source":"detector","confidence":0.91,"features":{"peak_g":7.2}},
         "resolved_by":"$USER_ID",
         "trail":[{"lat":48.1,"lng":11.5,"recorded_at":"2026-10-13T07:00:00Z","accuracy_m":null,"landmark":null},
                  {"lat":48.2,"lng":11.6,"recorded_at":"2026-10-13T07:58:00Z","accuracy_m":5,"landmark":"Marienplatz, Altstadt"}],
         "acks":[$ACK]}
    """

    const val SHARE_DATA = """
        {"kind":"vehicle_crash","status":"active","detected_at":"2026-10-13T07:58:00Z","share_expires_at":"2026-10-16T07:58:01Z",
         "resolved_at":null,"resolution":null,
         "incident_location":{"lat":48.1,"lng":11.5,"recorded_at":"2026-10-13T07:58:00Z","accuracy_m":null},
         "latest":{"lat":48.11,"lng":11.51,"recorded_at":"2026-10-13T07:59:00Z","accuracy_m":4,"landmark":"A9"},
         "trail":[],"server_time":"2026-10-13T08:00:00Z"}
    """
}
