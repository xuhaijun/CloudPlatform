
    create table t_accident_event (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        accident_desc text,
        enterprise_id varchar(32),
        event_id varchar(64) not null,
        latitude float(53),
        longitude float(53),
        media_count integer not null,
        media_expected_count integer not null,
        media_request_msg_id varchar(64),
        media_requested_at datetime(6),
        media_status enum ('MEDIA_ACCEPTED','MEDIA_ARCHIVED','MEDIA_FAILED','MEDIA_REQUESTED','REPORTED') not null,
        msg_id varchar(64),
        occurred_at datetime(6) not null,
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_fault_item (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        error_code varchar(16) not null,
        error_reason varchar(255),
        error_severity varchar(8),
        error_timestamp bigint,
        msg_count integer,
        vin varchar(32) not null,
        fault_record_id bigint not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_fault_record (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        error_num integer,
        latitude float(53),
        longitude float(53),
        max_severity integer,
        msg_id varchar(64),
        reported_at datetime(6) not null,
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_map_barrier (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        acked bit not null,
        area_code varchar(64),
        barrier_id varchar(64) not null,
        barrier_name varchar(128),
        barrier_polygon text,
        barrier_type varchar(8) not null,
        date_type varchar(8),
        effective_date date,
        enterprise_id varchar(32),
        msg_id varchar(64),
        received_at datetime(6),
        source varchar(8),
        speed_limit float(53),
        time_range text,
        primary key (id)
    ) engine=InnoDB;

    create table t_media_asset (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        camera_direction varchar(4),
        end_time datetime(6),
        event_id varchar(64),
        file_size bigint,
        kind varchar(16) not null,
        media_type varchar(8),
        request_msg_id varchar(64),
        start_time datetime(6),
        status varchar(16) not null,
        storage_path varchar(512),
        uploaded_by varchar(32),
        url varchar(512),
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_mqtt_message_log (
        id bigint not null,
        ack_code varchar(4),
        clock_skew_ms bigint,
        direction varchar(8),
        error_message varchar(512),
        latency_ms integer,
        mqtt_type varchar(32),
        msg_id varchar(64),
        payload mediumtext,
        process_status varchar(16),
        received_at datetime(6) not null,
        sender_id varchar(64),
        size_bytes integer,
        topic varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_navigation_route (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        current_latitude float(53),
        current_longitude float(53),
        end_latitude float(53),
        end_longitude float(53),
        enterprise_id varchar(32),
        msg_id varchar(64),
        path_points mediumtext,
        point_count integer,
        reported_at datetime(6) not null,
        start_latitude float(53),
        start_longitude float(53),
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_remote_driving_record (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        closed bit not null,
        driver varchar(64),
        driver_card varchar(64),
        driving_type varchar(8) not null,
        duration_seconds bigint,
        enterprise_id varchar(32),
        msg_id varchar(64),
        occurred_at datetime(6) not null,
        remote_cockpit_no varchar(64),
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_track_task (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        duration_seconds bigint,
        end_latitude float(53),
        end_longitude float(53),
        end_position varchar(255),
        enterprise_id varchar(32),
        finished_at datetime(6),
        last_msg_id varchar(64),
        start_latitude float(53),
        start_longitude float(53),
        start_position varchar(255),
        started_at datetime(6),
        status enum ('FINISHED','ORPHAN_FINISH','RUNNING') not null,
        task_id varchar(64) not null,
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_vehicle (
        vin varchar(32) not null,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        accident_count bigint not null,
        ad_version varchar(64),
        com_type varchar(8),
        device_sn varchar(64),
        enterprise_id varchar(32),
        fault_count bigint not null,
        hw_version varchar(64),
        last_base_at datetime(6),
        last_inh_at datetime(6),
        last_online_at datetime(6),
        last_state_at datetime(6),
        model varchar(64),
        online bit,
        plate_no varchar(16),
        time_sync varchar(8),
        vehicle_type varchar(16),
        version bigint,
        primary key (vin)
    ) engine=InnoDB;

    create table t_vehicle_state_snapshot (
        id bigint not null,
        battery_capacity float(53),
        charge_state varchar(8),
        endurance float(53),
        gnss_status varchar(8),
        mileage float(53),
        msg_id varchar(64),
        reported_at datetime(6) not null,
        soc float(53),
        temperature integer,
        tire_pressure varchar(512),
        vehicle_total_weight float(53),
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_vehicle_static_param (
        id bigint not null,
        created_at datetime(6) not null,
        updated_at datetime(6) not null,
        ad_version varchar(64),
        com_type varchar(8),
        hw_version varchar(64),
        msg_id varchar(64),
        reported_at datetime(6) not null,
        time_sync varchar(8),
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create table t_vehicle_track_point (
        id bigint not null,
        acc_speed float(53),
        altitude float(53),
        driving_mode integer,
        heading float(53),
        latitude float(53),
        longitude float(53),
        speed float(53),
        ts bigint not null,
        vin varchar(32) not null,
        primary key (id)
    ) engine=InnoDB;

    create index idx_accident_vin_time 
       on t_accident_event (vin, occurred_at);

    create index idx_accident_status 
       on t_accident_event (media_status, occurred_at);

    alter table t_accident_event 
       add constraint uk_accident_event_id unique (event_id);

    create index idx_fault_item_code 
       on t_fault_item (error_code, error_timestamp);

    create index idx_fault_item_record 
       on t_fault_item (fault_record_id);

    create index idx_fault_vin_time 
       on t_fault_record (vin, reported_at);

    create index idx_fault_severity 
       on t_fault_record (max_severity, reported_at);

    create index idx_fault_time 
       on t_fault_record (reported_at);

    create index idx_barrier_area_date 
       on t_map_barrier (area_code, effective_date);

    create index idx_barrier_type 
       on t_map_barrier (barrier_type);

    create index idx_barrier_date 
       on t_map_barrier (effective_date);

    alter table t_map_barrier 
       add constraint uk_barrier_id_date unique (barrier_id, effective_date);

    create index idx_media_event_dir 
       on t_media_asset (event_id, camera_direction);

    create index idx_media_vin_time 
       on t_media_asset (vin, start_time);

    create index idx_media_status 
       on t_media_asset (status);

    create index idx_msg_log_time 
       on t_mqtt_message_log (received_at);

    create index idx_msg_log_vin_time 
       on t_mqtt_message_log (sender_id, received_at);

    create index idx_msg_log_type 
       on t_mqtt_message_log (mqtt_type, received_at);

    create index idx_msg_log_msg_id 
       on t_mqtt_message_log (msg_id);

    create index idx_route_vin_time 
       on t_navigation_route (vin, reported_at);

    create index idx_route_msg_id 
       on t_navigation_route (msg_id);

    create index idx_remote_vin_time 
       on t_remote_driving_record (vin, occurred_at);

    create index idx_remote_type 
       on t_remote_driving_record (driving_type, occurred_at);

    create index idx_task_vin_status 
       on t_track_task (vin, status);

    create index idx_task_started_at 
       on t_track_task (started_at);

    create index idx_task_status_time 
       on t_track_task (status, started_at);

    alter table t_track_task 
       add constraint uk_task_id unique (task_id);

    create index idx_vehicle_enterprise 
       on t_vehicle (enterprise_id);

    create index idx_vehicle_online 
       on t_vehicle (online, last_base_at);

    create index idx_state_vin_time 
       on t_vehicle_state_snapshot (vin, reported_at);

    create index idx_inh_vin_time 
       on t_vehicle_static_param (vin, reported_at);

    create index idx_inh_msg_id 
       on t_vehicle_static_param (msg_id);

    create index idx_track_vin_ts 
       on t_vehicle_track_point (vin, ts);

    create index idx_track_ts 
       on t_vehicle_track_point (ts);

    alter table t_fault_item 
       add constraint FKnjma4saxm38dnyy2uwqxej4l1 
       foreign key (fault_record_id) 
       references t_fault_record (id);
