package org.jabref.gui.autosaveandbackup;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.TableColumn;

import org.jabref.gui.LibraryTab;
import org.jabref.gui.backup.BackupEntry;
import org.jabref.gui.maintable.BibEntryTableViewModel;
import org.jabref.gui.maintable.columns.MainTableColumn;
import org.jabref.logic.bibtex.InvalidFieldValueException;
import org.jabref.logic.exporter.AtomicFileWriter;
import org.jabref.logic.exporter.BibWriter;
import org.jabref.logic.exporter.BibtexDatabaseWriter;
import org.jabref.logic.exporter.SelfContainedSaveConfiguration;
import org.jabref.logic.preferences.CliPreferences;
import org.jabref.logic.util.CoarseChangeFilter;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibEntryTypesManager;
import org.jabref.model.metadata.SaveOrder;
import org.jabref.model.metadata.SelfContainedSaveOrder;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupManagerGit {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupManagerGit.class);

    private static final int DELAY_BETWEEN_BACKUP_ATTEMPTS_IN_SECONDS = 19;

    private static Set<BackupManagerGit> runningInstances = new HashSet<BackupManagerGit>();

    private final BibDatabaseContext bibDatabaseContext;
    private final CliPreferences preferences;
    private final ScheduledThreadPoolExecutor executor;
    private final CoarseChangeFilter changeFilter;
    private final BibEntryTypesManager entryTypesManager;
    private final LibraryTab libraryTab;
    private final Git git;

    private boolean needsBackup = false;

    BackupManagerGit(LibraryTab libraryTab, BibDatabaseContext bibDatabaseContext, BibEntryTypesManager entryTypesManager, CliPreferences preferences) throws IOException, GitAPIException {
        this.bibDatabaseContext = bibDatabaseContext;
        this.entryTypesManager = entryTypesManager;
        this.preferences = preferences;
        this.executor = new ScheduledThreadPoolExecutor(2);
        this.libraryTab = libraryTab;

        changeFilter = new CoarseChangeFilter(bibDatabaseContext);
        changeFilter.registerListener(this);

        // Ensure the backup directory exists
        File backupDir = preferences.getFilePreferences().getBackupDirectory().toFile();
        if (!backupDir.exists()) {
            boolean dirCreated = backupDir.mkdirs();
            if (dirCreated) {
                LOGGER.info("Created backup directory: " + backupDir);
            } else {
                LOGGER.error("Failed to create backup directory: " + backupDir);
            }
        }

        // Initialize Git repository in the backup directory
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        git = new Git(builder.setGitDir(new File(backupDir, ".git"))
                             .readEnvironment()
                             .findGitDir()
                             .build());

        if (git.getRepository().getObjectDatabase().exists()) {
            LOGGER.info("Git repository already exists");
        } else {
            Git.init().setDirectory(backupDir).call(); // Explicitly set the directory
            LOGGER.info("Initialized new Git repository");
        }
    }

    /**
     * Starts a new BackupManagerGit instance and begins the backup task.
     *
     * @param libraryTab the library tab
     * @param bibDatabaseContext the BibDatabaseContext to be backed up
     * @param entryTypesManager the BibEntryTypesManager
     * @param preferences the CLI preferences
     * @param originalPath the original path of the file to be backed up
     * @return the started BackupManagerGit instance
     * @throws IOException if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */

    public static BackupManagerGit start(LibraryTab libraryTab, BibDatabaseContext bibDatabaseContext, BibEntryTypesManager entryTypesManager, CliPreferences preferences, Path originalPath) throws IOException, GitAPIException {
        LOGGER.info("Starting backup manager for file: {}", originalPath);
        BackupManagerGit backupManagerGit = new BackupManagerGit(libraryTab, bibDatabaseContext, entryTypesManager, preferences);
        backupManagerGit.startBackupTask(originalPath, preferences.getFilePreferences().getBackupDirectory());
        runningInstances.add(backupManagerGit);
        return backupManagerGit;
    }

    /**
     * Shuts down the BackupManagerGit instances associated with the given BibDatabaseContext.
     *
     * @param bibDatabaseContext the BibDatabaseContext
     * @param createBackup whether to create a backup before shutting down
     * @param originalPath the original path of the file to be backed up
     */
    public static void shutdown(BibDatabaseContext bibDatabaseContext, boolean createBackup, Path originalPath) {
        runningInstances.stream()
                        .filter(instance -> instance.bibDatabaseContext == bibDatabaseContext)
                        .forEach(backupManager -> backupManager.shutdownGit(bibDatabaseContext.getDatabasePath().orElseThrow().getParent().resolve("backup"), createBackup, originalPath));

        // Remove the instances associated with the BibDatabaseContext after shutdown
        runningInstances.removeIf(instance -> instance.bibDatabaseContext == bibDatabaseContext);
    }

    /**
     * Starts the backup task that periodically checks for changes and commits them to the Git repository.
     *
     * @param backupDir the backup directory
     * @param originalPath the original path of the file to be backed up
     */

    void startBackupTask(Path originalPath, Path backupDir) {
        executor.scheduleAtFixedRate(
                () -> {
                    try {
                        LOGGER.info("Starting backup task for file: {}", originalPath);
                        performBackup(originalPath, backupDir);
                        LOGGER.info("Backup task completed for file: {}", originalPath);
                    } catch (IOException | GitAPIException e) {
                        LOGGER.error("Error during backup", e);
                    }
                },
                DELAY_BETWEEN_BACKUP_ATTEMPTS_IN_SECONDS,
                DELAY_BETWEEN_BACKUP_ATTEMPTS_IN_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * Performs the backup by checking for changes and committing them to the Git repository.
     *
     * @param backupDir the backup directory
     * @param originalPath the original path of the file to be backed up
     * @throws IOException if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */

    protected void performBackup(Path originalPath, Path backupDir) throws IOException, GitAPIException {
        LOGGER.info("Starting backup process for file: {}", originalPath);

        // Check if the file needs a backup by comparing it to the last commit
        boolean needsBackup = BackupManagerGit.backupGitDiffers(originalPath, backupDir);

        if (!needsBackup) {
            LOGGER.info("No changes detected. Backup not required for file: {}", originalPath);
            return;
        }

        try {
            Path backupFilePath = backupDir.resolve(FilenameUtils.removeExtension(originalPath.getFileName().toString()) + ".bak");
            if (!Files.exists(backupFilePath)) {
                Files.createFile(backupFilePath);
            }

            // l'ordre dans lequel les entrées BibTeX doivent être écrites dans le fichier de sauvegarde.
            // Si l'utilisateur a trié la table d'affichage des entrées dans JabRef, cet ordre est récupéré.
            // Sinon, un ordre par défaut est utilisé.
            // code similar to org.jabref.gui.exporter.SaveDatabaseAction.saveDatabase
            SelfContainedSaveOrder saveOrder = bibDatabaseContext
                    .getMetaData().getSaveOrder()
                    .map(so -> {
                        if (so.getOrderType() == SaveOrder.OrderType.TABLE) {
                            // We need to "flatten out" SaveOrder.OrderType.TABLE as BibWriter does not have access to preferences
                            List<TableColumn<BibEntryTableViewModel, ?>> sortOrder = libraryTab.getMainTable().getSortOrder();
                            return new SelfContainedSaveOrder(
                                    SaveOrder.OrderType.SPECIFIED,
                                    sortOrder.stream()
                                             .filter(col -> col instanceof MainTableColumn<?>)
                                             .map(column -> ((MainTableColumn<?>) column).getModel())
                                             .flatMap(model -> model.getSortCriteria().stream())
                                             .toList());
                        } else {
                            return SelfContainedSaveOrder.of(so);
                        }
                    })
                    .orElse(SaveOrder.getDefaultSaveOrder());

            // Elle configure la sauvegarde, en indiquant qu'aucune sauvegarde supplémentaire (backup) ne doit être créée,
            // que l'ordre de sauvegarde doit être celui défini, et que les entrées doivent être formatées selon les préférences
            // utilisateur.
            SelfContainedSaveConfiguration saveConfiguration = (SelfContainedSaveConfiguration) new SelfContainedSaveConfiguration()
                    .withMakeBackup(false)
                    .withSaveOrder(saveOrder)
                    .withReformatOnSave(preferences.getLibraryPreferences().shouldAlwaysReformatOnSave());

            // "Clone" the database context
            // We "know" that "only" the BibEntries might be changed during writing (see [org.jabref.logic.exporter.BibDatabaseWriter.savePartOfDatabase])
            // Chaque entrée BibTeX (comme un article, livre, etc.) est clonée en utilisant la méthode clone().
            // Cela garantit que les modifications faites pendant la sauvegarde n'affecteront pas l'entrée originale.
            List<BibEntry> list = bibDatabaseContext.getDatabase().getEntries().stream()
                                                    .map(BibEntry::clone)
                                                    .map(BibEntry.class::cast)
                                                    .toList();
            BibDatabase bibDatabaseClone = new BibDatabase(list);
            BibDatabaseContext bibDatabaseContextClone = new BibDatabaseContext(bibDatabaseClone, bibDatabaseContext.getMetaData());
            // Elle définit l'encodage à utiliser pour écrire le fichier. Cela garantit que les caractères spéciaux sont bien sauvegardés.
            Charset encoding = bibDatabaseContext.getMetaData().getEncoding().orElse(StandardCharsets.UTF_8);
            // We want to have successful backups only
            // Thus, we do not use a plain "FileWriter", but the "AtomicFileWriter"
            // Example: What happens if one hard powers off the machine (or kills the jabref process) during writing of the backup?
            //          This MUST NOT create a broken backup file that then jabref wants to "restore" from?
            try (Writer writer = new AtomicFileWriter(backupFilePath, encoding, false)) {
                BibWriter bibWriter = new BibWriter(writer, bibDatabaseContext.getDatabase().getNewLineSeparator());
                new BibtexDatabaseWriter(
                        bibWriter,
                        saveConfiguration,
                        preferences.getFieldPreferences(),
                        preferences.getCitationKeyPatternPreferences(),
                        entryTypesManager)
                        // we save the clone to prevent the original database (and thus the UI) from being changed
                        .saveDatabase(bibDatabaseContextClone);
            } catch (IOException e) {
                logIfCritical(backupFilePath, e);
            }

            // Add and commit changes
            git.add().addFilepattern(".").call();
            // Commit the staged changes
            RevCommit commit = git.commit()
                                  .setMessage("Backup at " + Instant.now().toString())
                                  .call();
            LOGGER.info("Backup committed with ID: {}", commit.getId().getName());
        } catch (IOException e) {
            LOGGER.warn("An error was encountered while writing the Backup File");
        }
    }

    private void logIfCritical(Path backupPath, IOException e) {
        Throwable innermostCause = e;
        while (innermostCause.getCause() != null) {
            innermostCause = innermostCause.getCause();
        }
        boolean isErrorInField = innermostCause instanceof InvalidFieldValueException;

        // do not print errors in field values into the log during autosave
        if (!isErrorInField) {
            LOGGER.error("Error while saving to file {}", backupPath, e);
        }
    }

    /**
     * Restores the backup from the specified commit.
     *
     * @param originalPath the original path of the file to be restored
     * @param backupDir the backup directory
     * @param commitId the commit ID to restore from
     */

    public static void restoreBackup(Path originalPath, Path backupDir, ObjectId commitId) {
        try {
            Git git = Git.open(backupDir.toFile());

            git.checkout().setStartPoint(commitId.getName()).setAllPaths(true).call();

            git.add().addFilepattern(".").call();

            git.commit().setMessage("Restored content from commit: " + commitId.getName()).call();

            Repository repository = git.getRepository();

            RevWalk revWalk = new RevWalk(repository);
            RevCommit commit = revWalk.parseCommit(commitId);

            Path backupFilePath = backupDir.resolve(FilenameUtils.removeExtension(originalPath.getFileName().toString()) + ".bak");

            LOGGER.info(backupFilePath.getFileName().toString());

            boolean fileFound = false;

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);

                // Loop through the files in the commit
                while (treeWalk.next()) {
                    String filePathInCommit = treeWalk.getPathString();

                    // Check if this file path matches the expected backup file path
                    if (filePathInCommit.equals(backupFilePath.getFileName().toString())) {
                        fileFound = true;

                        // If the file matches, restore it
                        ObjectId fileObjectId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repository.open(fileObjectId);

                        try (InputStream inputStream = loader.openStream()) {
                            Files.copy(inputStream, originalPath, StandardCopyOption.REPLACE_EXISTING);
                        }

                        LOGGER.info("Restored backup from Git repository for file: " + filePathInCommit);
                        break;
                    }
                }
                LOGGER.info("Restored backup from Git repository and committed the changes");
                // If the file was not found in the commit
                if (!fileFound) {
                    throw new java.io.FileNotFoundException("File not found in the commit: " + backupFilePath.getFileName().toString());
                }
            }
        } catch (IOException | GitAPIException e) {
            LOGGER.error("Error while restoring the backup", e);
        }
    }

    /**
     * Checks if there are differences between the original file and the backup.
     *
     * @param originalPath the original path of the file
     * @param backupDir the backup directory
     * @return true if there are differences, false otherwise
     * @throws IOException if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public static boolean backupGitDiffers(Path originalPath, Path backupDir) throws IOException, GitAPIException {
        // Ensure Git repository exists
        File repoDir = backupDir.toFile();
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .build();

        try (Git git = new Git(repository)) {
            // Resolve HEAD commit
            ObjectId headCommitId = repository.resolve("HEAD");
            if (headCommitId == null) {
                LOGGER.info("No head commit found. Creating first commit");
                Path backupFilePath = backupDir.resolve(FilenameUtils.removeExtension(originalPath.getFileName().toString()) + ".bak");

                Files.createFile(backupFilePath);
                Files.copy(originalPath, backupFilePath, StandardCopyOption.REPLACE_EXISTING);

                git.add().addFilepattern(".").call();
                git.commit().setMessage("Backup at " + Instant.now().toString()).call();

                return false;
            }

            // Attempt to retrieve the file content from the last commit
            ObjectLoader loader;
            try {
                LOGGER.info("File to check in repo : {}", originalPath.getFileName().toString());
                String fileToCheck = FilenameUtils.removeExtension(originalPath.getFileName().toString()) + ".bak";
                ObjectId fileId = repository.resolve("HEAD:" + fileToCheck);
                if (fileId == null) {
                    LOGGER.warn("Can't get head commit");
                    return false;
                }
                loader = repository.open(fileId);
            } catch (
                    MissingObjectException e) {
                LOGGER.warn("Can't get head commit");
                // File not found in the last commit; assume it differs
                return true;
            }

            // Read the content from the last commit
            String committedContent = new String(loader.getBytes(), StandardCharsets.UTF_8);

            // Read the current content of the file
            if (!Files.exists(originalPath)) {
                LOGGER.warn("File doesn't exist: {}", originalPath);
                // If the file doesn't exist in the working directory, it differs
                return true;
            }
            String currentContent = Files.readString(originalPath, StandardCharsets.UTF_8);

            LOGGER.info("Commited content : \n{}", committedContent);
            LOGGER.info("Current content : \n{}", currentContent);
            // Compare the current content to the committed content
            return !currentContent.equals(committedContent);
        }
    }

    /**
     * Shows the differences between the specified commit and the latest commit.
     *
     * @param originalPath the original path of the file
     * @param backupDir the backup directory
     * @param commitId the commit ID to compare with the latest commit
     * @return a list of DiffEntry objects representing the differences
     * @throws IOException if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */
    public List<DiffEntry> showDiffers(Path originalPath, Path backupDir, String commitId) throws IOException, GitAPIException {

        File repoDir = backupDir.toFile();
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .build();
        /*
        need a class to show the last ten backups indicating: date/ size/ number of entries
         */

        ObjectId oldCommit = repository.resolve(commitId);
        ObjectId newCommit = repository.resolve("HEAD");

        FileOutputStream fos = new FileOutputStream(FileDescriptor.out);
        DiffFormatter diffFr = new DiffFormatter(fos);
        diffFr.setRepository(repository);
        return diffFr.scan(oldCommit, newCommit);
    }

    /**
     * Retrieves the last n commits from the Git repository.
     *
     * @param backupDir the backup directory
     * @param n the number of commits to retrieve
     * @return a list of RevCommit objects representing the commits
     * @throws IOException if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */

    public static List<RevCommit> retrieveCommits(Path backupDir, int n) throws IOException, GitAPIException {
        List<RevCommit> retrievedCommits = new ArrayList<>();
        // Open Git repository
        try (Repository repository = Git.open(backupDir.toFile()).getRepository()) {
            // Use RevWalk to traverse commits
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit startCommit = revWalk.parseCommit(repository.resolve("HEAD"));
                revWalk.markStart(startCommit);

                int count = 0;
                for (RevCommit commit : revWalk) {
                    retrievedCommits.add(commit);
                    count++;
                    if (count == n) {
                        break; // Stop after collecting the required number of commits
                    }
                }
            }
        }

        // Reverse the list to have commits in the correct order
        Collections.reverse(retrievedCommits);
        return retrievedCommits;
    }

    /**
     * Retrieves detailed information about the specified commits.
     *
     * @param commits the list of commits to retrieve details for
     * @param backupDir the backup directory
     * @return a list of lists, each containing details about a commit
     * @throws IOException if an I/O error occurs
     * @throws GitAPIException if a Git API error occurs
     */

    public static List<BackupEntry> retrieveCommitDetails(List<RevCommit> commits, Path backupDir) throws IOException, GitAPIException {
        List<BackupEntry> commitDetails;
        try (Repository repository = Git.open(backupDir.toFile()).getRepository()) {
            commitDetails = new ArrayList<>();

            // Browse the list of commits given as a parameter
            for (RevCommit commit : commits) {
                // A list to stock details about the commit
                List<String> commitInfo = new ArrayList<>();
                commitInfo.add(commit.getName()); // ID of commit

                // Get the size of files changes by the commit
                String sizeFormatted;
                try (TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setRecursive(true);
                    long totalSize = 0;

                    while (treeWalk.next()) {
                        ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                        totalSize += loader.getSize(); // size in bytes
                    }

                    // Convert the size to Kb or Mb
                    sizeFormatted = (totalSize > 1024 * 1024)
                            ? String.format("%.2f Mo", totalSize / (1024.0 * 1024.0))
                            : String.format("%.2f Ko", totalSize / 1024.0);

                    commitInfo.add(sizeFormatted); // Add Formatted size
                }

                // adding date detail
                Date date = commit.getAuthorIdent().getWhen();
                commitInfo.add(date.toString());
                // Add list of details to the main list
                BackupEntry backupEntry = new BackupEntry(commit.getId(), commitInfo.get(0), commitInfo.get(2), commitInfo.get(1), 0);
                commitDetails.add(backupEntry);
            }
        }

        return commitDetails;
    }

    /**
     * Shuts down the JGit components and optionally creates a backup.
     *
     * @param createBackup whether to create a backup before shutting down
     * @param originalPath the original path of the file to be backed up
     */

    private void shutdownGit(Path backupDir, boolean createBackup, Path originalPath) {
        // Unregister the listener and shut down the change filter
        if (changeFilter != null) {
            changeFilter.unregisterListener(this);
            changeFilter.shutdown();
        }

        // Shut down the executor if it's not already shut down
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }

        // If backup is requested, ensure that we perform the Git-based backup
        if (createBackup) {
            try {
                // Ensure the backup is a recent one by performing the Git commit
                performBackup(originalPath, backupDir);
            } catch (IOException | GitAPIException e) {
                LOGGER.error("Error during Git backup on shutdown", e);
            }
        }
    }
}






