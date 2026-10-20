-- Voice messages carry a small waveform (bar heights 0..100) so the app can draw it before the audio has loaded.
alter table media add column waveform smallint[] check (waveform is null or cardinality(waveform) between 1 and 64);
