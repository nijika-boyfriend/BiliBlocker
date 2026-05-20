'use strict';

console.log('[java-ok] script-loaded');

setImmediate(function () {
  console.log('[java-ok] setImmediate');
  Java.perform(function () {
    console.log('[java-ok] ready');
  });
});
