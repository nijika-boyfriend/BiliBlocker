'use strict';

console.log('[comment-probe] script-loaded');

function log(message) {
  console.log('[comment-probe] ' + message);
}

function safe(fn, fallback) {
  try {
    return fn();
  } catch (e) {
    return fallback;
  }
}

setImmediate(function () {
  log('setImmediate');
  Java.perform(function () {
    var sampleCount = 0;
    var hookInstalled = false;

    function getClassName(obj) {
      return safe(function () {
        return obj.getClass().getName().toString();
      }, 'null');
    }

    function getResourceName(view) {
      return safe(function () {
        var id = view.getId();
        if (id === -1) return 'no-id';
        return view.getResources().getResourceEntryName(id).toString();
      }, 'id-error');
    }

    function findViewByName(root, idName) {
      return safe(function () {
        var context = root.getContext();
        var packageName = context.getPackageName().toString();
        var resId = root.getResources().getIdentifier(idName, 'id', packageName);
        if (!resId) return null;
        return root.findViewById(resId);
      }, null);
    }

    function describeValue(value) {
      if (value === null || value === undefined) return 'null';
      return safe(function () {
        if (typeof value === 'number' || typeof value === 'string') return String(value);
        var clsName = value.getClass().getName().toString();
        if (clsName === 'java.lang.String') return '"' + value.toString() + '"';
        if (clsName.indexOf('java.lang.') === 0 || clsName.indexOf('kotlin.') === 0) {
          return value.toString();
        }
        return clsName;
      }, '<describe-error>');
    }

    function dumpDeclaredFields(obj, label, limit) {
      if (!obj) return;
      var cls = safe(function () { return obj.getClass(); }, null);
      if (!cls) return;
      var fields = safe(function () { return cls.getDeclaredFields(); }, []);
      log(label + ' class=' + cls.getName());
      for (var i = 0; i < fields.length && i < limit; i++) {
        var field = fields[i];
        safe(function () {
          field.setAccessible(true);
          var name = field.getName().toString();
          var value = field.get(obj);
          log('  ' + label + '.' + name + ' = ' + describeValue(value));
        }, null);
      }
    }

    function extractAdapterData(adapter, position) {
      if (position < 0) return null;

      var direct = safe(function () {
        return adapter.getItem(position);
      }, null);
      if (direct) return direct;

      direct = safe(function () {
        return adapter.getData(position);
      }, null);
      if (direct) return direct;

      var fields = safe(function () {
        return adapter.getClass().getDeclaredFields();
      }, []);

      for (var i = 0; i < fields.length; i++) {
        var item = safe(function () {
          var field = fields[i];
          field.setAccessible(true);
          var value = field.get(adapter);
          if (!value) return null;
          if (!value.getClass().getName().toString().startsWith('java.util.')) return null;
          var size = value.size();
          if (position >= size) return null;
          return value.get(position);
        }, null);
        if (item) return item;
      }

      return null;
    }

    function hookCommentListAdapter() {
      var ViewHolder = Java.use('androidx.recyclerview.widget.RecyclerView$ViewHolder');
      var Adapter = Java.use('com.bilibili.app.comment3.ui.adapter.CommentListAdapter');
      var overloads = Adapter.onBindViewHolder.overloads;
      log('CommentListAdapter.onBindViewHolder overloads=' + overloads.length);

      overloads.forEach(function (overload, index) {
        log('hook overload[' + index + ']: ' + overload.argumentTypes.map(function (arg) {
          return arg.className;
        }).join(', '));

        overload.implementation = function () {
          var holder = arguments[0];
          var position = arguments.length > 1 ? parseInt(arguments[1], 10) : -1;
          var itemView = null;

          try {
            itemView = Java.cast(holder, ViewHolder).itemView.value;
          } catch (e) {
            log('cast holder failed: ' + e);
          }

          var replyButton = itemView ? findViewByName(itemView, 'reply_button') : null;
          var moreButton = itemView ? findViewByName(itemView, 'more_button') : null;
          var actions = itemView ? findViewByName(itemView, 'item_include_actions') : null;
          var richText = itemView ? findViewByName(itemView, 'item_include_rich_text') : null;

          log(
            'bind pos=' + position +
            ' holder=' + getClassName(holder) +
            ' item=' + (itemView ? getClassName(itemView) : 'null') +
            ' itemId=' + (itemView ? getResourceName(itemView) : 'null') +
            ' reply=' + !!replyButton +
            ' more=' + !!moreButton +
            ' actions=' + !!actions +
            ' rich=' + !!richText
          );

          if (sampleCount < 6) {
            dumpDeclaredFields(holder, 'holder', 16);
            var data = extractAdapterData(this, position);
            if (data) {
              log('adapterData=' + getClassName(data));
              dumpDeclaredFields(data, 'data', 20);
            } else {
              log('adapterData=null');
            }
            sampleCount++;
          }

          return overload.apply(this, arguments);
        };
      });

      hookInstalled = true;
    }

    function installWithRetry(attempt) {
      if (hookInstalled) return;
      try {
        hookCommentListAdapter();
        log('ready');
      } catch (e) {
        log('hook attempt ' + attempt + ' failed: ' + e);
        if (attempt < 10) {
          setTimeout(function () {
            installWithRetry(attempt + 1);
          }, 1000);
        }
      }
    }

    log('java-ready');
    setTimeout(function () {
      installWithRetry(1);
    }, 4000);
  });
});
