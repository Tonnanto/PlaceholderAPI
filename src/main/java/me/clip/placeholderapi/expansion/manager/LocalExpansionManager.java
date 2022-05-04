/*
 * This file is part of PlaceholderAPI
 *
 * PlaceholderAPI
 * Copyright (c) 2015 - 2021 PlaceholderAPI Team
 *
 * PlaceholderAPI free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PlaceholderAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package me.clip.placeholderapi.expansion.manager;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.stream.Collectors;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.events.ExpansionRegisterEvent;
import me.clip.placeholderapi.events.ExpansionUnregisterEvent;
import me.clip.placeholderapi.events.ExpansionsLoadedEvent;
import me.clip.placeholderapi.expansion.Cacheable;
import me.clip.placeholderapi.expansion.Cleanable;
import me.clip.placeholderapi.expansion.Configurable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Taskable;
import me.clip.placeholderapi.expansion.Version;
import me.clip.placeholderapi.expansion.VersionSpecific;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;
import me.clip.placeholderapi.util.FileUtil;
import me.clip.placeholderapi.util.Futures;
import me.clip.placeholderapi.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

public final class LocalExpansionManager implements Listener {

  @NotNull
  private static final String EXPANSIONS_FOLDER_NAME = "expansions";

  @NotNull
  private static final Set<MethodSignature> ABSTRACT_EXPANSION_METHODS = Arrays.stream(PlaceholderExpansion.class.getDeclaredMethods())
          .filter(method -> Modifier.isAbstract(method.getModifiers()))
          .map(method -> new MethodSignature(method.getName(), method.getParameterTypes()))
          .collect(Collectors.toSet());
  @NotNull
  private final List<PlaceholderExpansion> expansionQueue = new ArrayList<>();

  @NotNull
  private final File folder;
  @NotNull
  private final PlaceholderAPIPlugin plugin;

  @NotNull
  private final Map<String, PlaceholderExpansion> expansions = new ConcurrentHashMap<>();
  private final ReentrantLock expansionsLock = new ReentrantLock();
  
  private boolean loaded = false;

  public LocalExpansionManager(@NotNull final PlaceholderAPIPlugin plugin) {
    this.plugin = plugin;
    this.folder = new File(plugin.getDataFolder(), EXPANSIONS_FOLDER_NAME);

    if (!this.folder.exists() && !folder.mkdirs()) {
      plugin.getLogger().log(Level.WARNING, "failed to create expansions folder!");
    }
  }

  public void load(@NotNull final CommandSender sender) {
    registerAll(sender);
  }

  public void kill() {
    unregisterAll();
  }


  @NotNull
  public File getExpansionsFolder() {
    return folder;
  }

  @NotNull
  @Unmodifiable
  public Collection<String> getIdentifiers() {
    expansionsLock.lock();
    try {
      return ImmutableSet.copyOf(expansions.keySet());
    } finally {
      expansionsLock.unlock();
    }
  }

  @NotNull
  @Unmodifiable
  public Collection<PlaceholderExpansion> getExpansions() {
    expansionsLock.lock();
    try {
      return ImmutableSet.copyOf(expansions.values());
    } finally {
      expansionsLock.unlock();
    }
  }

  @Nullable
  public PlaceholderExpansion getExpansion(@NotNull final String identifier) {
    expansionsLock.lock();
    try {
      return expansions.get(identifier.toLowerCase(Locale.ROOT));
    } finally {
      expansionsLock.unlock();
    }
  }

  @NotNull
  public Optional<PlaceholderExpansion> findExpansionByName(@NotNull final String name) {
    expansionsLock.lock();
    try {
      PlaceholderExpansion bestMatch = null;
      for (Map.Entry<String, PlaceholderExpansion> entry : expansions.entrySet()) {
        PlaceholderExpansion expansion = entry.getValue();
        if (expansion.getName().equalsIgnoreCase(name)) {
          bestMatch = expansion;
          break;
        }
      }
      return Optional.ofNullable(bestMatch);
    } finally {
      expansionsLock.unlock();
    }
  }

  @NotNull
  public Optional<PlaceholderExpansion> findExpansionByIdentifier(
      @NotNull final String identifier) {
    return Optional.ofNullable(getExpansion(identifier));
  }
  
  @ApiStatus.Internal
  public boolean addToQueue(@NotNull final PlaceholderExpansion expansion) {
    if (!expansion.canRegister()) {
      return false;
    }
    
    if (loaded) {
      return loadExpansion(expansion);
    }
    
    plugin.getLogger().info("Adding " + expansion.getIdentifier() + " to Expansion Queue...");
    return expansionQueue.add(expansion);
  }

  @ApiStatus.Internal
  public boolean unregister(@NotNull final PlaceholderExpansion expansion) {
    if (expansions.remove(expansion.getIdentifier()) == null) {
      return false;
    }

    Bukkit.getPluginManager().callEvent(new ExpansionUnregisterEvent(expansion));

    if (expansion instanceof Listener) {
      HandlerList.unregisterAll((Listener) expansion);
    }

    if (expansion instanceof Taskable) {
      ((Taskable) expansion).stop();
    }

    if (expansion instanceof Cacheable) {
      ((Cacheable) expansion).clear();
    }

    if (plugin.getPlaceholderAPIConfig().isCloudEnabled()) {
      plugin.getCloudExpansionManager().findCloudExpansionByName(expansion.getName())
          .ifPresent(cloud -> {
            cloud.setHasExpansion(false);
            cloud.setShouldUpdate(false);
          });
    }

    return true;
  }

  private boolean loadExpansion(@NotNull final PlaceholderExpansion expansion) {
    final String identifier = expansion.getIdentifier().toLowerCase(Locale.ROOT);

    if (expansion instanceof Configurable) {
      Map<String, Object> defaults = ((Configurable) expansion).getDefaults();
      String pre = "expansions." + identifier + ".";
      FileConfiguration cfg = plugin.getConfig();
      boolean save = false;

      if (defaults != null) {
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
          if (entry.getKey() == null || entry.getKey().isEmpty()) {
            continue;
          }

          if (entry.getValue() == null) {
            if (cfg.contains(pre + entry.getKey())) {
              save = true;
              cfg.set(pre + entry.getKey(), null);
            }
          } else {
            if (!cfg.contains(pre + entry.getKey())) {
              save = true;
              cfg.set(pre + entry.getKey(), entry.getValue());
            }
          }
        }
      }

      if (save) {
        plugin.saveConfig();
        plugin.reloadConfig();
      }
    }
    
    if (expansion instanceof VersionSpecific) {
      VersionSpecific nms = (VersionSpecific) expansion;
      Version serverVersion = PlaceholderAPIPlugin.getServerVersion();
      if (!nms.isCompatibleWith(serverVersion)) {
        plugin.getLogger().warning("PlaceholderExpansion " + expansion.getIdentifier() +
            " is incompatible with your server version (" + serverVersion.getVersion() + ").");
        return false;
      }
    }
    
    final PlaceholderExpansion removed = getExpansion(identifier);
    if (removed != null && !removed.unregister()) {
      plugin.getLogger().warning("PlaceholderExpansion " + expansion.getIdentifier() +
          "could not be registered as it has been already loaded and couldn't be unloaded.");
      return false;
    }
    
    final ExpansionRegisterEvent event = new ExpansionRegisterEvent(expansion);
    Bukkit.getPluginManager().callEvent(event);
    
    if (event.isCancelled()) {
      return false;
    }
    
    expansionsLock.lock();
    try {
      expansions.put(identifier, expansion);
    } finally {
      expansionsLock.unlock();
    }
    
    if (expansion instanceof Listener) {
      plugin.getLogger().info("PlaceholderExpansion " + expansion.getIdentifier() +
          " registered as a Listener for Events.");
      Bukkit.getPluginManager().registerEvents((Listener) expansion, plugin);
    }
    
    plugin.getLogger().info("Successfully registered " + expansion.getIdentifier() +
        " [" + expansion.getVersion() + "]");
    
    if (expansion instanceof Taskable) {
      ((Taskable) expansion).start();
    }
    
    if (plugin.getPlaceholderAPIConfig().isCloudEnabled()) {
      final Optional<CloudExpansion> optionalCloudExpansion = plugin.getCloudExpansionManager()
          .findCloudExpansionByName(identifier);
      if (optionalCloudExpansion.isPresent()) {
        CloudExpansion cloudExpansion = optionalCloudExpansion.get();
        cloudExpansion.setHasExpansion(true);
        cloudExpansion.setShouldUpdate(
            !cloudExpansion.getLatestVersion().equals(expansion.getVersion())
        );
      }
    }
    
    return true;
  }

  private void registerAll(@NotNull final CommandSender sender) {
    plugin.getLogger().info("Placeholder expansion registration initializing...");
    loaded = true;
    
    Futures.onMainThread(plugin, collectExpansions(), (expansions, exception) -> {
      if (exception != null) {
        plugin.getLogger().log(Level.SEVERE, "failed to load class files of expansions", exception);
        return;
      }
      
      final List<PlaceholderExpansion> registered = expansions.stream()
          .filter(Objects::nonNull)
          .filter(this::loadExpansion)
          .collect(Collectors.toList());

      final long needsUpdate = registered.stream()
          .map(expansion -> plugin.getCloudExpansionManager().findCloudExpansionByName(expansion.getName()).orElse(null))
          .filter(Objects::nonNull)
          .filter(CloudExpansion::shouldUpdate)
          .count();

      StringBuilder message = new StringBuilder(registered.size() == 0 ? "&6" : "&a")
          .append(registered.size())
          .append(' ')
          .append("placeholder hook(s) registered!");
      
      if (needsUpdate > 0) {
        message.append(' ')
            .append("&6")
            .append(needsUpdate)
            .append(' ')
            .append("placeholder hook(s) have an update available.");
      }
      
      Msg.msg(sender, message.toString());

      Bukkit.getPluginManager().callEvent(new ExpansionsLoadedEvent(registered));
    });
  }

  private void unregisterAll() {
    for (final PlaceholderExpansion expansion : Sets.newHashSet(expansions.values())) {
      if (expansion.persist()) {
        continue;
      }

      expansion.unregister();
    }
  }
  
  @NotNull
  public CompletableFuture<@NotNull List<@Nullable PlaceholderExpansion>> collectExpansions() {
    File[] files = folder.listFiles((dir, name) -> name.endsWith(".jar"));
    if (files == null) {
      return CompletableFuture.completedFuture(expansionQueue);
    }
    
    return CompletableFuture.supplyAsync(() -> {
      Arrays.stream(files)
          .map(this::findExpansions)
          .forEach(expansionQueue::add);
      
      return expansionQueue;
    });
  }
  
  @Nullable
  public PlaceholderExpansion findExpansions(@NotNull File file) {
    try {
      final Class<? extends PlaceholderExpansion> expansion = FileUtil.findClass(file,
          PlaceholderExpansion.class);

      if (expansion == null) {
        plugin.getLogger()
            .severe("Failed to load Expansion " + file.getName() + ". File does not have " +
                "a class which extends PlaceholderExpansion.");
        return null;
      }

      Set<MethodSignature> expansionMethods = Arrays.stream(expansion.getDeclaredMethods())
          .map(method -> new MethodSignature(method.getName(), method.getParameterTypes()))
          .collect(Collectors.toSet());
      if (!expansionMethods.containsAll(ABSTRACT_EXPANSION_METHODS)) {
        plugin.getLogger()
            .severe("Failed to load Expansion " + file.getName() + ". File does not have " +
                "the required methods declared for a PlaceholderExpansion.");
        return null;
      }

      return expansion.getDeclaredConstructor().newInstance();
    } catch (Exception ex) {
      plugin.getLogger()
          .severe("Failed to load Expansion " + file.getName() + ". (Is a dependency missing?)");
      plugin.getLogger().severe("Cause: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
      return null;
    }
  }
  
  @NotNull
  public CompletableFuture<@Nullable PlaceholderExpansion> findExpansion(@NotNull final File file) {
    return CompletableFuture.supplyAsync(() -> findExpansions(file));
  }

  @EventHandler
  public void onQuit(@NotNull final PlayerQuitEvent event) {
    for (final PlaceholderExpansion expansion : getExpansions()) {
      if (!(expansion instanceof Cleanable)) {
        continue;
      }

      ((Cleanable) expansion).cleanup(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onPluginDisable(@NotNull final PluginDisableEvent event) {
    final String name = event.getPlugin().getName();
    if (name.equals(plugin.getName())) {
      return;
    }

    for (final PlaceholderExpansion expansion : getExpansions()) {
      if (!name.equalsIgnoreCase(expansion.getRequiredPlugin())) {
        continue;
      }

      expansion.unregister();
      plugin.getLogger().info("Unregistered placeholder expansion: " + expansion.getName());
    }
  }

}
