# AutismTV

**AutismTV** is a Fabric mod for **Minecraft 1.21.11** that turns a real desktop display into an in-world panel you can place, align, and share with other players.

It lets you capture a real monitor, render it onto a panel inside Minecraft, save anchor points for placement, and open a session so other clients can view the same panel. If the server also has **AutismTV** installed, session traffic is routed through the server for client-to-client communication. If not, the mod falls back to its direct peer session mode.

## Features

### **In-World Screen Panels**

AutismTV renders a live captured display onto a panel placed in the Minecraft world. This is not just a flat menu overlay. It behaves like a positioned in-world surface that you can place and align in 3D space.

### **Anchor-Based Placement**

You can save anchor points and snap panels back onto them later. This makes it easier to place screens on walls, build repeatable setups, or restore a layout without manually lining everything up every time.

### **Session Hosting and Joining**

The mod includes a **Session Hub** where you can:

- browse available sessions
- create your own session
- join remote sessions
- manage your active hosted or joined session

Sessions can be **open** or **password-protected**.

### **Per-Viewer Permissions**

Hosts can manage who is allowed to **click** and who is allowed to **type**. Permissions are handled *per viewer*, not just per session, which gives you much finer control over how shared panels are used.

### **Server-Backed Relay Support**

If the server also has **AutismTV** installed, the mod uses server networking for communication between clients.

In that mode, the server relays:

- session discovery
- host and viewer state
- panel transform and placement data
- streamed frame updates
- remote mouse input
- remote keyboard input
- per-viewer permission changes

### **Fallback Peer Mode**

If the server does **not** have the mod installed, AutismTV can still use its built-in direct client-hosted session transport where available.

## Commands

AutismTV currently includes these client-side commands:

- `/panel spawn`
- `/panel delete`
- `/panel align`
- `/anchor spawn`
- `/anchor delete`
- `/anchor align`

These are used to create, remove, and re-align panels and anchors in the world.

## Included UI

AutismTV includes several in-game screens for setup and session management:

- **Session Hub**
- **Sharescreen Settings**
- **Viewer Permissions**

The **Session Hub** is available from the options screen and is the main place for browsing sessions, creating sessions, and managing live viewers.

## Configuration

The mod stores settings and placement data in config files so your setup can persist between sessions.

This includes settings such as:

- selected display
- capture resolution
- framerate
- panel distance
- panel size
- panel rotation
- billboard mode
- saved panel anchors

## Communication Modes

### **Server Relay Mode**

Used when the server also has **AutismTV** installed.

This is the preferred mode for multiplayer because the server handles communication between connected clients.

### **Peer Session Mode**

Used when the server does **not** have the mod installed.

In this mode, clients communicate through the built-in direct session transport instead.

## Important Notes

- This is a *client-focused* mod with client rendering and client UI features.
- Server relay support only activates when the server also has **AutismTV** installed.
- Features that capture a real display depend on local system support and permissions.
- This mod targets **Minecraft 1.21.11**, **Fabric**, and **Java 21**.

## Requirements

- **Minecraft 1.21.11**
- **Fabric Loader**
- **Java 21**

## Why Use It

AutismTV is designed for players who want immersive in-world monitors, collaborative viewing setups, shared control panels, or desktop-to-world display systems inside Minecraft without relying on a traditional HUD overlay.