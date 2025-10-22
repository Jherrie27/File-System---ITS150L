import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import javax.swing.tree.*;

// ==================== DATA STRUCTURES ====================

class FileNode implements Serializable {
    private static final long serialVersionUID = 1L;
    String name;
    boolean isDirectory;
    String content;
    int size;
    Date created;
    Date modified;
    Map<String, FileNode> children;
    int allocatedBlocks;
    int startBlock;
    
    public FileNode(String name, boolean isDirectory) {
        this.name = name;
        this.isDirectory = isDirectory;
        this.content = "";
        this.size = 0;
        this.created = new Date();
        this.modified = new Date();
        this.children = new HashMap<>();
        this.allocatedBlocks = 0;
        this.startBlock = -1;
    }
}

class FileAllocationTable {
    private static final int TOTAL_BLOCKS = 1000;
    private static final int BLOCK_SIZE = 512; // bytes
    private boolean[] blocks;
    private Map<Integer, String> blockOwners;
    
    public FileAllocationTable() {
        blocks = new boolean[TOTAL_BLOCKS];
        blockOwners = new HashMap<>();
    }
    
    public int allocateBlocks(int blocksNeeded, String fileName) {
        if (blocksNeeded == 0) return 0;
        
        // Find first available block
        for (int i = 0; i < TOTAL_BLOCKS; i++) {
            boolean canAllocate = true;
            if (i + blocksNeeded > TOTAL_BLOCKS) break;
            
            for (int j = 0; j < blocksNeeded; j++) {
                if (blocks[i + j]) {
                    canAllocate = false;
                    break;
                }
            }
            
            if (canAllocate) {
                for (int j = 0; j < blocksNeeded; j++) {
                    blocks[i + j] = true;
                    blockOwners.put(i + j, fileName);
                }
                return i;
            }
        }
        return -1; // No space available
    }
    
    public void deallocateBlocks(int startBlock, int count) {
        for (int i = startBlock; i < startBlock + count && i < TOTAL_BLOCKS; i++) {
            blocks[i] = false;
            blockOwners.remove(i);
        }
    }
    
    public int getFreeBlocks() {
        int count = 0;
        for (boolean block : blocks) {
            if (!block) count++;
        }
        return count;
    }
    
    public int getUsedBlocks() {
        return TOTAL_BLOCKS - getFreeBlocks();
    }
    
    public double getUsagePercentage() {
        return (getUsedBlocks() * 100.0) / TOTAL_BLOCKS;
    }
    
    public int getTotalBlocks() {
        return TOTAL_BLOCKS;
    }
    
    public int getBlockSize() {
        return BLOCK_SIZE;
    }
}

class VirtualFileSystem {
    private FileNode root;
    private FileNode currentDirectory;
    private String currentPath;
    private FileAllocationTable fat;
    private SimpleDateFormat dateFormat;
    
    public VirtualFileSystem() {
        root = new FileNode("root", true);
        currentDirectory = root;
        currentPath = "/";
        fat = new FileAllocationTable();
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    }
    
    public FileNode getRoot() { return root; }
    public FileNode getCurrentDirectory() { return currentDirectory; }
    public String getCurrentPath() { return currentPath; }
    public FileAllocationTable getFAT() { return fat; }
    
    public boolean createFile(String fileName, String content) {
        if (currentDirectory.children.containsKey(fileName)) {
            return false;
        }
        
        FileNode newFile = new FileNode(fileName, false);
        newFile.content = content;
        newFile.size = content.length();
        
        int blocksNeeded = (int) Math.ceil((double) newFile.size / fat.getBlockSize());
        if (blocksNeeded == 0) blocksNeeded = 1;
        
        int startBlock = fat.allocateBlocks(blocksNeeded, fileName);
        if (startBlock == -1) {
            return false; // Out of space
        }
        
        newFile.startBlock = startBlock;
        newFile.allocatedBlocks = blocksNeeded;
        currentDirectory.children.put(fileName, newFile);
        currentDirectory.modified = new Date();
        
        return true;
    }
    
    public boolean createDirectory(String dirName) {
        if (currentDirectory.children.containsKey(dirName)) {
            return false;
        }
        
        FileNode newDir = new FileNode(dirName, true);
        currentDirectory.children.put(dirName, newDir);
        currentDirectory.modified = new Date();
        
        return true;
    }
    
    public FileNode getFile(String fileName) {
        return currentDirectory.children.get(fileName);
    }
    
    public boolean writeFile(String fileName, String content) {
        FileNode file = currentDirectory.children.get(fileName);
        if (file == null || file.isDirectory) {
            return false;
        }
        
        // Deallocate old blocks
        if (file.allocatedBlocks > 0) {
            fat.deallocateBlocks(file.startBlock, file.allocatedBlocks);
        }
        
        // Allocate new blocks
        file.content = content;
        file.size = content.length();
        
        int blocksNeeded = (int) Math.ceil((double) file.size / fat.getBlockSize());
        if (blocksNeeded == 0) blocksNeeded = 1;
        
        int startBlock = fat.allocateBlocks(blocksNeeded, fileName);
        if (startBlock == -1) {
            return false;
        }
        
        file.startBlock = startBlock;
        file.allocatedBlocks = blocksNeeded;
        file.modified = new Date();
        currentDirectory.modified = new Date();
        
        return true;
    }
    
    public boolean deleteItem(String name) {
        FileNode item = currentDirectory.children.get(name);
        if (item == null) return false;
        
        if (!item.isDirectory && item.allocatedBlocks > 0) {
            fat.deallocateBlocks(item.startBlock, item.allocatedBlocks);
        }
        
        currentDirectory.children.remove(name);
        currentDirectory.modified = new Date();
        return true;
    }
    
    public boolean changeDirectory(String dirName) {
        if (dirName.equals("..")) {
            if (currentPath.equals("/")) return true;
            
            int lastSlash = currentPath.lastIndexOf('/');
            if (lastSlash == 0) {
                currentPath = "/";
                currentDirectory = root;
            } else {
                currentPath = currentPath.substring(0, lastSlash);
                currentDirectory = findNode(currentPath);
            }
            return true;
        }
        
        FileNode dir = currentDirectory.children.get(dirName);
        if (dir == null || !dir.isDirectory) return false;
        
        currentDirectory = dir;
        if (currentPath.equals("/")) {
            currentPath = "/" + dirName;
        } else {
            currentPath = currentPath + "/" + dirName;
        }
        return true;
    }
    
    private FileNode findNode(String path) {
        if (path.equals("/")) return root;
        
        String[] parts = path.split("/");
        FileNode current = root;
        
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!current.children.containsKey(part)) return null;
            current = current.children.get(part);
        }
        return current;
    }
    
    public void exportFileSystem(String filePath) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath))) {
            oos.writeObject(root);
        }
    }
    
    public void importFileSystem(String filePath) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath))) {
            root = (FileNode) ois.readObject();
            currentDirectory = root;
            currentPath = "/";
        }
    }
}

// ==================== GUI COMPONENTS ====================

public class FileSystemGUI extends JFrame {
    private VirtualFileSystem vfs;
    private JTree directoryTree;
    private JTable fileTable;
    private JTextArea fileContentArea;
    private JLabel statusLabel;
    private JProgressBar diskUsageBar;
    private JLabel diskInfoLabel;
    private DefaultTreeModel treeModel;
    private DefaultTableModel tableModel;
    
    public FileSystemGUI() {
        vfs = new VirtualFileSystem();
        initializeGUI();
        refreshAll();
    }
    
    private void initializeGUI() {
        setTitle("File System Development Project - OS Project #2");
        setSize(1400, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        
        // Set look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Main layout
        setLayout(new BorderLayout(10, 10));
        
        // Top toolbar
        add(createToolbar(), BorderLayout.NORTH);
        
        // Center split pane
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setLeftComponent(createLeftPanel());
        mainSplit.setRightComponent(createRightPanel());
        mainSplit.setDividerLocation(300);
        add(mainSplit, BorderLayout.CENTER);
        
        // Bottom status bar
        add(createStatusBar(), BorderLayout.SOUTH);
    }
    
    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        toolbar.setBackground(new Color(240, 240, 240));
        toolbar.setBorder(new EmptyBorder(5, 5, 5, 5));
        
        JButton newFileBtn = createButton("New File", "icons/file.png");
        newFileBtn.addActionListener(e -> createNewFile());
        
        JButton newFolderBtn = createButton("New Folder", "icons/folder.png");
        newFolderBtn.addActionListener(e -> createNewFolder());
        
        JButton deleteBtn = createButton("Delete", "icons/delete.png");
        deleteBtn.addActionListener(e -> deleteSelected());
        
        JButton refreshBtn = createButton("Refresh", "icons/refresh.png");
        refreshBtn.addActionListener(e -> refreshAll());
        
        JButton importBtn = createButton("Import FS", "icons/import.png");
        importBtn.addActionListener(e -> importFileSystem());
        
        JButton exportBtn = createButton("Export FS", "icons/export.png");
        exportBtn.addActionListener(e -> exportFileSystem());
        
        toolbar.add(newFileBtn);
        toolbar.add(newFolderBtn);
        toolbar.add(deleteBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(refreshBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(importBtn);
        toolbar.add(exportBtn);
        
        return toolbar;
    }
    
    private JButton createButton(String text, String iconPath) {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        return btn;
    }
    
    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new TitledBorder("Directory Tree"));
        
        // Create tree
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("root");
        treeModel = new DefaultTreeModel(rootNode);
        directoryTree = new JTree(treeModel);
        directoryTree.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        
        directoryTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) directoryTree.getLastSelectedPathComponent();
            if (node != null) {
                navigateToNode(node);
            }
        });
        
        JScrollPane treeScroll = new JScrollPane(directoryTree);
        panel.add(treeScroll, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        
        // Top: File list
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBorder(new TitledBorder("Files in Current Directory"));
        
        String[] columns = {"Name", "Type", "Size (bytes)", "Modified", "Blocks"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        fileTable = new JTable(tableModel);
        fileTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.getTableHeader().setReorderingAllowed(false);
        
        fileTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedFile();
                }
            }
        });
        
        JScrollPane tableScroll = new JScrollPane(fileTable);
        tablePanel.add(tableScroll, BorderLayout.CENTER);
        
        // Bottom: File content editor
        JPanel editorPanel = new JPanel(new BorderLayout());
        editorPanel.setBorder(new TitledBorder("File Content Editor"));
        
        fileContentArea = new JTextArea();
        fileContentArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        fileContentArea.setTabSize(4);
        JScrollPane editorScroll = new JScrollPane(fileContentArea);
        editorPanel.add(editorScroll, BorderLayout.CENTER);
        
        JPanel editorButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveBtn = new JButton("Save File");
        saveBtn.addActionListener(e -> saveCurrentFile());
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> fileContentArea.setText(""));
        editorButtons.add(saveBtn);
        editorButtons.add(clearBtn);
        editorPanel.add(editorButtons, BorderLayout.SOUTH);
        
        rightSplit.setTopComponent(tablePanel);
        rightSplit.setBottomComponent(editorPanel);
        rightSplit.setDividerLocation(300);
        
        panel.add(rightSplit, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout(10, 5));
        statusBar.setBorder(new EmptyBorder(5, 10, 5, 10));
        statusBar.setBackground(new Color(240, 240, 240));
        
        statusLabel = new JLabel("Ready");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        
        JPanel diskPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        diskPanel.setBackground(new Color(240, 240, 240));
        
        diskUsageBar = new JProgressBar(0, 100);
        diskUsageBar.setStringPainted(true);
        diskUsageBar.setPreferredSize(new Dimension(200, 20));
        
        diskInfoLabel = new JLabel();
        diskInfoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        
        diskPanel.add(new JLabel("Disk Usage:"));
        diskPanel.add(diskUsageBar);
        diskPanel.add(diskInfoLabel);
        
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(diskPanel, BorderLayout.EAST);
        
        return statusBar;
    }
    
    private void createNewFile() {
        String fileName = JOptionPane.showInputDialog(this, "Enter file name:", "New File", JOptionPane.PLAIN_MESSAGE);
        if (fileName != null && !fileName.trim().isEmpty()) {
            if (vfs.createFile(fileName.trim(), "")) {
                setStatus("File created: " + fileName);
                refreshAll();
            } else {
                JOptionPane.showMessageDialog(this, "Failed to create file. It may already exist or disk is full.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void createNewFolder() {
        String folderName = JOptionPane.showInputDialog(this, "Enter folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
        if (folderName != null && !folderName.trim().isEmpty()) {
            if (vfs.createDirectory(folderName.trim())) {
                setStatus("Folder created: " + folderName);
                refreshAll();
            } else {
                JOptionPane.showMessageDialog(this, "Failed to create folder. It may already exist.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void deleteSelected() {
        int row = fileTable.getSelectedRow();
        if (row >= 0) {
            String name = (String) fileTable.getValueAt(row, 0);
            int confirm = JOptionPane.showConfirmDialog(this, "Delete '" + name + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                if (vfs.deleteItem(name)) {
                    setStatus("Deleted: " + name);
                    fileContentArea.setText("");
                    refreshAll();
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to delete item.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        } else {
            JOptionPane.showMessageDialog(this, "Please select an item to delete.", "No Selection", JOptionPane.WARNING_MESSAGE);
        }
    }
    
    private void openSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row >= 0) {
            String name = (String) fileTable.getValueAt(row, 0);
            String type = (String) fileTable.getValueAt(row, 1);
            
            if (type.equals("Directory")) {
                vfs.changeDirectory(name);
                refreshAll();
            } else {
                FileNode file = vfs.getFile(name);
                if (file != null) {
                    fileContentArea.setText(file.content);
                    fileContentArea.setCaretPosition(0);
                    setStatus("Opened: " + name);
                }
            }
        }
    }
    
    private void saveCurrentFile() {
        int row = fileTable.getSelectedRow();
        if (row >= 0) {
            String name = (String) fileTable.getValueAt(row, 0);
            String type = (String) fileTable.getValueAt(row, 1);
            
            if (type.equals("File")) {
                String content = fileContentArea.getText();
                if (vfs.writeFile(name, content)) {
                    setStatus("Saved: " + name);
                    refreshAll();
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to save file. Disk may be full.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(this, "Please select a file to save.", "Not a File", JOptionPane.WARNING_MESSAGE);
            }
        } else {
            JOptionPane.showMessageDialog(this, "Please select a file first.", "No Selection", JOptionPane.WARNING_MESSAGE);
        }
    }
    
    private void navigateToNode(DefaultMutableTreeNode node) {
        TreeNode[] path = node.getPath();
        StringBuilder newPath = new StringBuilder();
        
        for (int i = 1; i < path.length; i++) {
            newPath.append("/").append(path[i].toString());
        }
        
        // Navigate to root first
        while (!vfs.getCurrentPath().equals("/")) {
            vfs.changeDirectory("..");
        }
        
        // Navigate to target
        if (newPath.length() > 0) {
            String[] parts = newPath.toString().split("/");
            for (String part : parts) {
                if (!part.isEmpty()) {
                    vfs.changeDirectory(part);
                }
            }
        }
        
        refreshFileTable();
        updateDiskUsage();
        setStatus("Current directory: " + vfs.getCurrentPath());
    }
    
    private void refreshAll() {
        refreshTree();
        refreshFileTable();
        updateDiskUsage();
    }
    
    private void refreshTree() {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("root");
        buildTree(vfs.getRoot(), rootNode);
        treeModel.setRoot(rootNode);
        expandAllNodes(directoryTree);
    }
    
    private void buildTree(FileNode node, DefaultMutableTreeNode treeNode) {
        for (FileNode child : node.children.values()) {
            if (child.isDirectory) {
                DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child.name);
                treeNode.add(childNode);
                buildTree(child, childNode);
            }
        }
    }
    
    private void expandAllNodes(JTree tree) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }
    
    private void refreshFileTable() {
        tableModel.setRowCount(0);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        
        FileNode current = vfs.getCurrentDirectory();
        for (FileNode node : current.children.values()) {
            String type = node.isDirectory ? "Directory" : "File";
            String size = node.isDirectory ? "-" : String.valueOf(node.size);
            String blocks = node.isDirectory ? "-" : String.valueOf(node.allocatedBlocks);
            tableModel.addRow(new Object[]{
                node.name,
                type,
                size,
                sdf.format(node.modified),
                blocks
            });
        }
    }
    
    private void updateDiskUsage() {
        FileAllocationTable fat = vfs.getFAT();
        double usage = fat.getUsagePercentage();
        diskUsageBar.setValue((int) usage);
        
        if (usage < 50) {
            diskUsageBar.setForeground(new Color(76, 175, 80));
        } else if (usage < 80) {
            diskUsageBar.setForeground(new Color(255, 152, 0));
        } else {
            diskUsageBar.setForeground(new Color(244, 67, 54));
        }
        
        diskInfoLabel.setText(String.format("%d/%d blocks (%.1f%%)", 
            fat.getUsedBlocks(), fat.getTotalBlocks(), usage));
    }
    
    private void setStatus(String message) {
        statusLabel.setText(message);
    }
    
    private void exportFileSystem() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export File System");
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                vfs.exportFileSystem(chooser.getSelectedFile().getAbsolutePath());
                JOptionPane.showMessageDialog(this, "File system exported successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Failed to export: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void importFileSystem() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import File System");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                vfs.importFileSystem(chooser.getSelectedFile().getAbsolutePath());
                refreshAll();
                JOptionPane.showMessageDialog(this, "File system imported successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Failed to import: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FileSystemGUI gui = new FileSystemGUI();
            gui.setVisible(true);
        });
    }
}