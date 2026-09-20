import os
import glob

replacements = {
    "com.vsp.internetspeedmeter.BroadcastReciever": "com.vsp.internetspeedmeter.broadcastreceiver",
    "com.vsp.internetspeedmeter.Room": "com.vsp.internetspeedmeter.room",
    "com.vsp.internetspeedmeter.Recyclerview": "com.vsp.internetspeedmeter.recyclerview"
}

project_dir = "/root/InternetSpeedMeter/app/src/main/java/com/vsp/internetspeedmeter"

# Move directories
dirs_to_move = {
    "BroadcastReciever": "broadcastreceiver",
    "Room": "room",
    "Recyclerview": "recyclerview"
}

for old, new in dirs_to_move.items():
    old_path = os.path.join(project_dir, old)
    new_path = os.path.join(project_dir, new)
    if os.path.exists(old_path):
        os.rename(old_path, new_path)
        print(f"Moved {old} to {new}")

# Find and replace in all Kotlin and XML files
target_extensions = [".kt", ".xml"]
search_dirs = ["/root/InternetSpeedMeter/app/src/main"]

for s_dir in search_dirs:
    for root, dirs, files in os.walk(s_dir):
        for file in files:
            if any(file.endswith(ext) for ext in target_extensions):
                file_path = os.path.join(root, file)
                with open(file_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                
                new_content = content
                for old_pkg, new_pkg in replacements.items():
                    new_content = new_content.replace(old_pkg, new_pkg)
                
                if new_content != content:
                    with open(file_path, 'w', encoding='utf-8') as f:
                        f.write(new_content)
                    print(f"Updated {file_path}")
