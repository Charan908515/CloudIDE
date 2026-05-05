# What is CloudIDE?

CloudIDE is a place to write and edit code that follows you between your computer and your phone. Think of it as a code editor with the convenience of Google Docs: your work is saved to your Google account, and you can pick up where you left off on whatever device you happen to be holding.

It comes in two pieces that work together:

- A **desktop app** (Windows, macOS, Linux) that looks and feels like Visual Studio Code — the popular tool millions of programmers use every day.
- A **mobile app** for Android that lets you view, edit, and run files in any project the desktop has saved.

Both apps sign in with the same Google account, and both read from and write to the same place: a folder called **CloudIDE** inside your Google Drive.

---

## What can you actually do with it?

### On the desktop

- Open any folder of files and start editing — code, text, configuration files, anything textual.
- Use a real terminal (PowerShell, Command Prompt, bash) inside the app, like the one in Windows Terminal but built in.
- Search across every file in your project at once.
- Track every change you make using Git, the standard tool teams use to keep a history of edits — without having to learn the command line for it.
- Run a single file (Python, JavaScript, etc.) and see the output, all without leaving the editor.
- Push your project to Google Drive with one click. Or set it to push automatically every time you save.

### On the phone

- See every project you've pushed from desktop, right on your phone.
- Open any file and read or edit it on the go.
- Add new files, rename them, or delete them.
- When you sync, only the files that actually changed get uploaded — not the whole project. So a one-line edit takes a second to sync, not a minute.
- Or, going the other direction: take a folder of files **already on your phone**, and push it up to the cloud as a brand-new project. From there, your desktop can pull it down and you can keep editing on the bigger screen.

---

## How does the sync work?

The simplest way to picture it:

1. Your desktop has a folder of files (your project).
2. CloudIDE makes a copy of that folder inside your Google Drive, under a top-level folder called **CloudIDE**.
3. When you change a file on the desktop and push, only that file gets re-uploaded — the rest stays as it was.
4. When you open the phone, the app reads the same Drive folder and shows you the same project. You edit a file there, hit sync, and the change goes back up.
5. If your desktop catches up later, it pulls down only the files that the phone changed.

You'll always see a small note in the corner of the app telling you what's happening: "3 files changed", "synced", or if there's a conflict (you and another device both edited the same file), it asks you what to do instead of guessing.

There's no separate server in the middle — everything goes directly between your devices and Google Drive. We don't see your files, and we don't store them on a server we run.

---

## Who is this for?

- Someone who's learning to code and wants their work to follow them between school, home, and a friend's computer without having to remember to copy files around.
- Someone who already uses tools like Visual Studio Code but wants their personal projects backed up automatically, without having to learn Git.
- Someone who likes to jot down a quick edit on their phone during a commute and want it ready when they sit down at the desktop.
- Someone who finds GitHub a bit too formal — Git is built for teams and reviewed code, and CloudIDE is built for "I just want my files to be safe and follow me."

CloudIDE is **not** trying to replace GitHub for sharing finished, polished code with teammates. The two solve different problems. CloudIDE is the personal scratchpad. GitHub is where you publish.

---

## What about privacy?

- Your code lives in **your** Google Drive. Not ours.
- The app talks directly to Google. We don't operate a server that handles your files.
- The only thing the app needs from Google is permission to read and write a folder called CloudIDE in your Drive — nothing else, nothing outside that folder.
- You can sign out at any time, or revoke the app's access from your Google account settings if you change your mind.

---

## What you'll need to get started

- A Google account (the same one on both devices).
- The CloudIDE desktop app installed on your computer.
- The CloudIDE Android app on your phone.
- An internet connection when you sync. The desktop and phone both work fine offline; they just queue changes until they can reach Drive again.

That's it. Open a folder on the desktop, click "Sign in with Google" at the bottom, then "Initialize Drive sync." Open the phone app, sign in with the same account, and your project is right there.

---

## Why "CloudIDE"?

"IDE" is the term programmers use for a code editor with extra tools built in (the I, D, and E stand for *Integrated Development Environment*, but nobody actually says that out loud). The "Cloud" part is the bit where your work gets quietly saved to your Google account in the background. Put together: a code editor that already knows how to keep your files safe in the cloud, without you having to think about it.
