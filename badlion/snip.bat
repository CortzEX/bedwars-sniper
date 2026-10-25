@echo off
rem blsnip control from a terminal, for when the in-game chat channel is not
rem answering. Rewrites blsnip.properties next to this file; the loader picks
rem the change up within one poll.
rem
rem   snip target 2Dance
rem   snip queue on
rem   snip show

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0snip.ps1" %*
