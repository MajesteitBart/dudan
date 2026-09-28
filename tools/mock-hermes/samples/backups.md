I'd use restic for this. It encrypts everything on your server before it leaves, only uploads changed data, and talks to B2 through its S3-compatible API. The steps assume Debian or Ubuntu with systemd; tell me if your server runs something else.

### 1. Create a bucket and a key in B2

In the Backblaze dashboard, create a private bucket, for example `my-server-backups`. Set its lifecycle rule to "Keep only the last version of the file". Otherwise the data restic deletes stays in the bucket as hidden versions, and you keep paying for it.

Then create an application key that can only access this bucket. Write down the keyID, the applicationKey (B2 shows it once) and the bucket's S3 endpoint, such as `s3.eu-central-003.backblazeb2.com`.

### 2. Install restic

```bash
sudo apt update && sudo apt install -y restic
restic version
```

### 3. Store the credentials and create the repository

```bash
sudo mkdir -p /etc/restic
sudo tee /etc/restic/b2.env > /dev/null <<'EOF'
AWS_ACCESS_KEY_ID=your-keyID
AWS_SECRET_ACCESS_KEY=your-applicationKey
RESTIC_REPOSITORY=s3:s3.eu-central-003.backblazeb2.com/my-server-backups
RESTIC_PASSWORD_FILE=/etc/restic/password
EOF
sudo sh -c 'openssl rand -base64 32 > /etc/restic/password'
sudo chmod 600 /etc/restic/b2.env /etc/restic/password
sudo sh -c 'set -a; . /etc/restic/b2.env; restic init'
```

Replace the key, endpoint and bucket name with your own. Then copy the contents of `/etc/restic/password` into your password manager. Without that password nobody can restore these backups, you included.

### 4. Write the backup script and run it once

```bash
sudo tee /usr/local/bin/restic-backup > /dev/null <<'EOF'
#!/bin/sh
set -eu
set -a; . /etc/restic/b2.env; set +a
restic backup /etc /home /var/www --exclude-caches
restic forget --keep-daily 7 --keep-weekly 4 --keep-monthly 6 --prune
EOF
sudo chmod 700 /usr/local/bin/restic-backup
sudo /usr/local/bin/restic-backup
```

Change the paths to the folders that matter on your server. For a database, dump it to a file first with `pg_dump` or `mysqldump` and back up that file. Copying the live data directory can give you a backup that won't start.

### 5. Schedule it with a systemd timer

```bash
sudo tee /etc/systemd/system/restic-backup.service > /dev/null <<'EOF'
[Unit]
Description=Restic backup to Backblaze B2
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/restic-backup
EOF

sudo tee /etc/systemd/system/restic-backup.timer > /dev/null <<'EOF'
[Unit]
Description=Run the restic backup

[Timer]
OnCalendar=*-*-* 03:00:00
RandomizedDelaySec=30min
Persistent=true

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now restic-backup.timer
systemctl list-timers restic-backup.timer
```

The `OnCalendar` line sets the frequency. This one runs every night around 03:00, and `Persistent=true` makes up for a run the server missed while it was off. Pick a different schedule below and I'll send the matching timer line and `forget` policy.

```openui-lang
root = Card([header, form, followUps])
header = CardHeader("Backup frequency", "I'll adjust step 4 and 5 to match")
form = Form("schedule", buttons, [frequencyField, retentionField])
buttons = Buttons([submit])
submit = Button("Update my timer", Action([@ToAssistant("Adjust my restic timer and retention to this schedule.")]), "primary")
frequencyField = FormControl("Frequency", frequency)
frequency = RadioGroup("frequency", [hourly, sixHours, daily, weekly], "daily")
hourly = RadioItem("Hourly", "Busy databases or uploads", "hourly")
sixHours = RadioItem("Every 6 hours", "Frequent changes, fewer snapshots", "every 6 hours")
daily = RadioItem("Daily", "Right for most servers", "daily")
weekly = RadioItem("Weekly", "Servers that rarely change", "weekly")
retentionField = FormControl("Keep snapshots for", retention, "Leave empty and I'll suggest one")
retention = Select("retention", [SelectItem("1 week", "1 week"), SelectItem("1 month", "1 month"), SelectItem("6 months", "6 months"), SelectItem("1 year", "1 year")], "Choose a period")
followUps = FollowUpBlock([f1, f2, f3])
f1 = FollowUpItem("How do I restore a single file?")
f2 = FollowUpItem("Alert me when a backup fails")
f3 = FollowUpItem("What will B2 cost for 200 GB?")
```
